// UC-G31 §6.6 / UC-G32 / UC-G33 — fraud-case detail + decide flow.
//
// Layout (UC-G31 §6.6):
//   - header: caseId, status, score badge, SLA countdown, actions.
//   - transaction panel: amount, currency, debtor/creditor, reference.
//   - score breakdown: sanctions / VoP / velocity rules / anomaly.
//   - account-context panel: 30-day volume, counterparties, deviations.
//   - timeline of state transitions.
//   - held payments listing.
//   - decision panel (when decided) or Decide button (when OPEN/ASSIGNED).
//
// Decide flow (UC-G32 / UC-G33):
//   1. operator opens DecideCaseModal.
//   2. picks decision (CONFIRMED_FRAUD or DISMISSED) + ≥50-char notes +
//      tick "criteria evaluated" checkbox.
//   3. computes notesHash = sha256(NFC(notes)) and runs SCA with class
//      `admin-action`, action `DECIDE_FRAUD`, resourceType `case`,
//      resourceId <caseId>, decision, notesHash. (UC-G51 SCA modal.)
//   4. POST /cases/{id}/decide with the resulting Step-Up-Token.
//   5. on 401 step-up-required → re-run SCA.
//      on 403 dl-mismatch → toast + close (defensive; should not happen if
//      operator notes match what was hashed).
//      on 409 case-already-decided → toast + reload detail.
//   6. assign / unassign do NOT need SCA per spec §6 step 4.

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useAuth } from '../../auth/AuthContext';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

type Status = 'OPEN' | 'ASSIGNED' | 'CONFIRMED_FRAUD' | 'DISMISSED';
type Decision = 'CONFIRMED_FRAUD' | 'DISMISSED';

interface CaseSummary {
    caseId: string;
    status: Status;
    score: number | string;
    scoreBand?: 'LOW' | 'MEDIUM' | 'HIGH';
    amount: string;
    currency: string;
    holderNameMasked?: string;
    debtorAccountId?: string;
    creditorIbanMasked?: string;
    triggeredRules?: string[];
    assignee?: string | null;
    createdAt: string;
    slaDeadline: string;
    slaBreached?: boolean;
    paymentId?: string;
}

interface CaseDetail {
    summary?: CaseSummary;
    // The Java DTO inlines the summary fields under `summary`; the OpenAPI
    // contract flattens them into the root via `allOf`. We accept both shapes.
    caseId?: string;
    status?: Status;
    score?: number | string;
    scoreBand?: 'LOW' | 'MEDIUM' | 'HIGH';
    amount?: string;
    currency?: string;
    holderNameMasked?: string;
    debtorAccountId?: string;
    creditorIbanMasked?: string;
    triggeredRules?: string[];
    assignee?: string | null;
    createdAt?: string;
    slaDeadline?: string;
    slaBreached?: boolean;
    paymentId?: string;
    transaction?: {
        paymentId: string;
        debtorIbanMasked?: string;
        creditorIban?: string;
        creditorName?: string;
        amount: string;
        currency: string;
        reference?: string;
        executionMode?: string;
        initiatedAt?: string;
    };
    scoreBreakdown?: Record<string, unknown>;
    timeline?: TimelineEntry[];
    heldPayments?: HeldPayment[];
    decision?: {
        decidedBy?: string;
        decidedAt?: string;
        decision?: Decision;
        notesSummary?: string;
        notesHash?: string;
    };
}

interface TimelineEntry {
    at: string;
    type: string;
    actor?: string;
    note?: string;
}

interface HeldPayment {
    paymentId: string;
    amount: string;
    currency: string;
    status: string;
}

interface CaseContext {
    windowDays: number;
    totalVolume?: { amount: string; currency: string };
    uniqueCounterparties: number;
    newBeneficiariesCount?: number;
    avgTransactionSize?: { amount: string; currency: string };
    deviations?: { indicator: string; severity: string; note?: string }[];
}

export function FraudCaseDetailPage() {
    const { t, i18n } = useTranslation();
    const { caseId = '' } = useParams<{ caseId: string }>();
    const { operatorId } = useAuth();
    const { showProblem, showInfo } = useToasts();

    const [detail, setDetail] = useState<CaseDetail | null>(null);
    const [context, setContext] = useState<CaseContext | null>(null);
    const [loading, setLoading] = useState(true);
    const [decideOpen, setDecideOpen] = useState(false);
    const reloadRef = useRef(0);

    const reload = useCallback(() => {
        reloadRef.current += 1;
        setLoading(true);
        Promise.all([
            api<CaseDetail>(`/api/v1/admin/fraud/cases/${encodeURIComponent(caseId)}`),
            api<CaseContext>(`/api/v1/admin/fraud/cases/${encodeURIComponent(caseId)}/context`).catch(
                () => null
            ),
        ])
            .then(([d, c]) => {
                setDetail(d);
                setContext(c);
            })
            .catch((e) => showProblem(e))
            .finally(() => setLoading(false));
    }, [caseId, showProblem]);

    useEffect(() => {
        reload();
    }, [reload]);

    const summary = mergedSummary(detail);
    const status = summary?.status;
    const isDecidable = status === 'OPEN' || status === 'ASSIGNED';
    const score = typeof summary?.score === 'string' ? Number(summary.score) : summary?.score ?? 0;
    const band = summary?.scoreBand ?? bandFor(score);

    async function selfAssign() {
        try {
            await api(`/api/v1/admin/fraud/cases/${encodeURIComponent(caseId)}/assign`, {
                method: 'POST',
                body: { assignee: 'me' },
            });
            showInfo(t('fraud.selfAssign'));
            reload();
        } catch (e) {
            showProblem(e);
        }
    }

    async function unassign() {
        try {
            await api(`/api/v1/admin/fraud/cases/${encodeURIComponent(caseId)}/assign`, {
                method: 'POST',
                body: { assignee: 'unassigned' },
            });
            showInfo(t('fraud.unassign'));
            reload();
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="case-title">
            <Link to="/fraud" className="text-sm text-jrpay-navy hover:underline">
                ← {t('fraud.queueTitle')}
            </Link>

            {loading && !detail && <p className="mt-4 text-slate-500">{t('common.loading')}</p>}

            {summary && (
                <>
                    <header className="mt-2 flex flex-wrap items-baseline justify-between gap-3">
                        <div>
                            <h1
                                id="case-title"
                                className="text-2xl font-semibold text-jrpay-navy"
                            >
                                {t('fraud.case')}{' '}
                                <span className="font-mono text-base">{summary.caseId}</span>
                            </h1>
                            <p className="text-sm text-slate-600 mt-1">
                                {summary.holderNameMasked && (
                                    <span className="mr-2">{summary.holderNameMasked}</span>
                                )}
                                <span className={statusBadgeClass(summary.status)}>
                                    {summary.status}
                                </span>
                                <span className={`ml-2 ${scoreClass(band)}`}>
                                    {Number.isFinite(score) ? score.toFixed(2) : '—'}
                                </span>
                            </p>
                        </div>

                        <div className="flex flex-wrap gap-2">
                            {summary.assignee !== operatorId && operatorId && (
                                <button className="btn-secondary" onClick={selfAssign}>
                                    {t('fraud.selfAssign')}
                                </button>
                            )}
                            {summary.assignee && (
                                <button className="btn-secondary" onClick={unassign}>
                                    {t('fraud.unassign')}
                                </button>
                            )}
                            {isDecidable && (
                                <button
                                    className="btn-primary"
                                    onClick={() => setDecideOpen(true)}
                                >
                                    {t('fraud.decideCase')}
                                </button>
                            )}
                        </div>
                    </header>

                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-4">
                        <div className="lg:col-span-2 space-y-4">
                            <TransactionPanel detail={detail!} locale={i18n.language} />
                            <ScoreBreakdownPanel detail={detail!} />
                            <TimelinePanel timeline={detail?.timeline ?? []} locale={i18n.language} />
                            {detail?.heldPayments && detail.heldPayments.length > 0 && (
                                <HeldPaymentsPanel
                                    items={detail.heldPayments}
                                    locale={i18n.language}
                                />
                            )}
                            {detail?.decision && <DecisionPanel decision={detail.decision} locale={i18n.language} />}
                        </div>
                        <div className="space-y-4">
                            <SlaPanel
                                createdAt={summary.createdAt}
                                slaDeadline={summary.slaDeadline}
                                breached={summary.slaBreached === true || isPast(summary.slaDeadline)}
                                locale={i18n.language}
                            />
                            <ContextPanel context={context} locale={i18n.language} />
                        </div>
                    </div>
                </>
            )}

            {decideOpen && summary && (
                <DecideCaseModal
                    caseId={summary.caseId}
                    onClose={() => setDecideOpen(false)}
                    onDecided={() => {
                        setDecideOpen(false);
                        reload();
                    }}
                />
            )}
        </section>
    );
}

// ───────────────────────────────────────────────────────── Decide modal ──

interface DecideForm {
    decision: Decision | '';
    notes: string;
    confirmedCriteria: boolean;
}

function DecideCaseModal({
    caseId,
    onClose,
    onDecided,
}: {
    caseId: string;
    onClose: () => void;
    onDecided: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [form, setForm] = useState<DecideForm>({
        decision: '',
        notes: '',
        confirmedCriteria: false,
    });
    const [submitting, setSubmitting] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [scaInputs, setScaInputs] = useState<{
        action: 'DECIDE_FRAUD';
        resourceType: 'case';
        resourceId: string;
        decision: Decision;
        notesHash: string;
    } | null>(null);

    const notesValid = form.notes.length >= 50;
    const canSubmit = form.decision !== '' && notesValid && form.confirmedCriteria;

    async function startScaFlow() {
        if (!canSubmit || form.decision === '') return;
        setSubmitting(true);
        try {
            const notesHash = await sha256HexNfc(form.notes);
            setScaInputs({
                action: 'DECIDE_FRAUD',
                resourceType: 'case',
                resourceId: caseId,
                decision: form.decision,
                notesHash,
            });
            setScaOpen(true);
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    async function postDecision(stepUpToken: string) {
        if (form.decision === '') return;
        try {
            await api(`/api/v1/admin/fraud/cases/${encodeURIComponent(caseId)}/decide`, {
                method: 'POST',
                stepUpToken,
                body: {
                    decision: form.decision,
                    notes: form.notes,
                    confirmedCriteria: true,
                    ...(form.decision === 'CONFIRMED_FRAUD'
                        ? { freezeReason: 'FRAUD_INVESTIGATION' }
                        : {}),
                },
            });
            showInfo(
                form.decision === 'CONFIRMED_FRAUD'
                    ? t('fraud.confirmFraud')
                    : t('fraud.dismiss')
            );
            onDecided();
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
                // Server still wants SCA — re-open the SCA modal with same inputs.
                setScaOpen(true);
                setSubmitting(false);
                return;
            }
            showProblem(e);
            setSubmitting(false);
        }
    }

    return (
        <>
            {!scaOpen && (
                <Modal titleId="decide-title" onClose={onClose}>
                    <h2 id="decide-title" className="text-lg font-semibold text-jrpay-navy">
                        {t('fraud.decideTitle')}
                    </h2>

                    <fieldset className="mt-4 space-y-2">
                        <legend className="sr-only">Decision</legend>
                        <label className="flex items-center gap-2 cursor-pointer">
                            <input
                                type="radio"
                                name="decision"
                                value="CONFIRMED_FRAUD"
                                checked={form.decision === 'CONFIRMED_FRAUD'}
                                onChange={() =>
                                    setForm((f) => ({ ...f, decision: 'CONFIRMED_FRAUD' }))
                                }
                            />
                            <span className="font-medium">{t('fraud.confirmFraud')}</span>
                        </label>
                        <label className="flex items-center gap-2 cursor-pointer">
                            <input
                                type="radio"
                                name="decision"
                                value="DISMISSED"
                                checked={form.decision === 'DISMISSED'}
                                onChange={() =>
                                    setForm((f) => ({ ...f, decision: 'DISMISSED' }))
                                }
                            />
                            <span className="font-medium">{t('fraud.dismiss')}</span>
                        </label>
                    </fieldset>

                    <label htmlFor="decide-notes" className="block text-sm font-medium mt-4">
                        {t('fraud.decideNotesLabel')}
                    </label>
                    <textarea
                        id="decide-notes"
                        className="input mt-1"
                        rows={5}
                        value={form.notes}
                        onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
                        aria-describedby="decide-notes-hint decide-notes-counter"
                    />
                    <div className="flex justify-between text-xs mt-1">
                        <p id="decide-notes-hint" className="text-slate-500">
                            {t('fraud.decideNotesHint')}
                        </p>
                        <p
                            id="decide-notes-counter"
                            className={notesValid ? 'text-jrpay-green' : 'text-jrpay-red'}
                        >
                            {form.notes.length} / 50
                        </p>
                    </div>

                    <label className="flex items-start gap-2 mt-4 text-sm cursor-pointer">
                        <input
                            type="checkbox"
                            checked={form.confirmedCriteria}
                            onChange={(e) =>
                                setForm((f) => ({
                                    ...f,
                                    confirmedCriteria: e.target.checked,
                                }))
                            }
                        />
                        <span>{t('fraud.confirmedCriteria')}</span>
                    </label>

                    <div className="mt-6 flex justify-end gap-2">
                        <button className="btn-secondary" onClick={onClose} disabled={submitting}>
                            {t('common.cancel')}
                        </button>
                        <button
                            className="btn-primary"
                            disabled={!canSubmit || submitting}
                            onClick={startScaFlow}
                        >
                            {t('fraud.decideSubmit')}
                        </button>
                    </div>
                </Modal>
            )}

            {scaOpen && scaInputs && (
                <ScaModal
                    inputs={{ class: 'admin-action', ...scaInputs }}
                    onResolve={(token) => {
                        setScaOpen(false);
                        void postDecision(token);
                    }}
                    onCancel={() => {
                        setScaOpen(false);
                        setSubmitting(false);
                    }}
                />
            )}
        </>
    );
}

// ───────────────────────────────────────────────────────── Sub-panels ──

function TransactionPanel({ detail, locale }: { detail: CaseDetail; locale: string }) {
    const tx = detail.transaction;
    if (!tx) return null;
    return (
        <Panel title="Transaction">
            <dl className="grid grid-cols-2 gap-y-2 text-sm">
                <DtDd label="Payment id">
                    <code className="text-xs">{tx.paymentId}</code>
                </DtDd>
                <DtDd label="Amount">
                    <span className="tabular-nums">{formatAmount(tx.amount, tx.currency, locale)}</span>
                </DtDd>
                <DtDd label="Debtor">
                    <code className="text-xs">{tx.debtorIbanMasked ?? '—'}</code>
                </DtDd>
                <DtDd label="Creditor IBAN">
                    <code className="text-xs">{tx.creditorIban ?? '—'}</code>
                </DtDd>
                <DtDd label="Creditor name">{tx.creditorName ?? '—'}</DtDd>
                <DtDd label="Mode">{tx.executionMode ?? '—'}</DtDd>
                {tx.reference && <DtDd label="Reference">{tx.reference}</DtDd>}
                {tx.initiatedAt && (
                    <DtDd label="Initiated">{formatDateTime(tx.initiatedAt, locale)}</DtDd>
                )}
            </dl>
        </Panel>
    );
}

function ScoreBreakdownPanel({ detail }: { detail: CaseDetail }) {
    const sb = detail.scoreBreakdown;
    if (!sb) return null;
    return (
        <Panel title="Score breakdown">
            <pre className="text-xs bg-slate-50 p-3 rounded overflow-x-auto">
                {JSON.stringify(sb, null, 2)}
            </pre>
        </Panel>
    );
}

function TimelinePanel({ timeline, locale }: { timeline: TimelineEntry[]; locale: string }) {
    if (timeline.length === 0) {
        return (
            <Panel title="Timeline">
                <p className="text-sm text-slate-500">—</p>
            </Panel>
        );
    }
    return (
        <Panel title="Timeline">
            <ol className="space-y-2 text-sm" aria-label="Case timeline">
                {timeline.map((e, i) => (
                    <li key={i} className="flex gap-3 border-l-2 border-slate-200 pl-3">
                        <span className="text-xs text-slate-500 w-32 shrink-0 tabular-nums">
                            {formatDateTime(e.at, locale)}
                        </span>
                        <span>
                            <span className="font-medium">{e.type}</span>
                            {e.actor && <span className="text-slate-500 ml-2">({e.actor})</span>}
                            {e.note && <span className="block text-slate-600">{e.note}</span>}
                        </span>
                    </li>
                ))}
            </ol>
        </Panel>
    );
}

function HeldPaymentsPanel({ items, locale }: { items: HeldPayment[]; locale: string }) {
    return (
        <Panel title="Held payments">
            <table className="w-full text-sm">
                <thead className="text-slate-700">
                    <tr>
                        <th scope="col" className="text-left py-1">
                            Payment id
                        </th>
                        <th scope="col" className="text-left py-1">
                            Amount
                        </th>
                        <th scope="col" className="text-left py-1">
                            Status
                        </th>
                    </tr>
                </thead>
                <tbody>
                    {items.map((p) => (
                        <tr key={p.paymentId} className="border-t border-slate-100">
                            <td className="py-1">
                                <code className="text-xs">{p.paymentId}</code>
                            </td>
                            <td className="py-1 tabular-nums">
                                {formatAmount(p.amount, p.currency, locale)}
                            </td>
                            <td className="py-1">{p.status}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </Panel>
    );
}

function DecisionPanel({
    decision,
    locale,
}: {
    decision: NonNullable<CaseDetail['decision']>;
    locale: string;
}) {
    return (
        <Panel title="Decision">
            <dl className="grid grid-cols-2 gap-y-2 text-sm">
                <DtDd label="Outcome">
                    <span
                        className={
                            decision.decision === 'CONFIRMED_FRAUD'
                                ? 'badge badge-high'
                                : 'badge badge-neutral'
                        }
                    >
                        {decision.decision}
                    </span>
                </DtDd>
                <DtDd label="By">{decision.decidedBy ?? '—'}</DtDd>
                {decision.decidedAt && (
                    <DtDd label="At">{formatDateTime(decision.decidedAt, locale)}</DtDd>
                )}
                {decision.notesSummary && (
                    <DtDd label="Notes">
                        <p className="whitespace-pre-wrap">{decision.notesSummary}</p>
                    </DtDd>
                )}
                {decision.notesHash && (
                    <DtDd label="Notes hash">
                        <code className="text-xs">{decision.notesHash}</code>
                    </DtDd>
                )}
            </dl>
        </Panel>
    );
}

function SlaPanel({
    createdAt,
    slaDeadline,
    breached,
    locale,
}: {
    createdAt: string;
    slaDeadline: string;
    breached: boolean;
    locale: string;
}) {
    const remaining = Math.max(0, Date.parse(slaDeadline) - Date.now());
    const minutes = Math.floor(remaining / 60_000);
    return (
        <Panel title="SLA">
            <dl className="grid grid-cols-2 gap-y-2 text-sm">
                <DtDd label="Received">{formatDateTime(createdAt, locale)}</DtDd>
                <DtDd label="Deadline">{formatDateTime(slaDeadline, locale)}</DtDd>
                <DtDd label="Status">
                    {breached ? (
                        <span className="badge badge-high">Breached</span>
                    ) : (
                        <span className="badge badge-low">{minutes} min remaining</span>
                    )}
                </DtDd>
            </dl>
        </Panel>
    );
}

function ContextPanel({ context, locale }: { context: CaseContext | null; locale: string }) {
    if (!context) return null;
    return (
        <Panel title={`Account context (${context.windowDays} d)`}>
            <dl className="grid grid-cols-2 gap-y-2 text-sm">
                {context.totalVolume && (
                    <DtDd label="Total volume">
                        {formatAmount(
                            context.totalVolume.amount,
                            context.totalVolume.currency,
                            locale
                        )}
                    </DtDd>
                )}
                <DtDd label="Counterparties">{context.uniqueCounterparties}</DtDd>
                {context.newBeneficiariesCount != null && (
                    <DtDd label="New beneficiaries">{context.newBeneficiariesCount}</DtDd>
                )}
                {context.avgTransactionSize && (
                    <DtDd label="Avg size">
                        {formatAmount(
                            context.avgTransactionSize.amount,
                            context.avgTransactionSize.currency,
                            locale
                        )}
                    </DtDd>
                )}
            </dl>
            {context.deviations && context.deviations.length > 0 && (
                <ul className="mt-3 space-y-1 text-sm">
                    {context.deviations.map((d, i) => (
                        <li key={i}>
                            <span
                                className={
                                    d.severity === 'HIGH'
                                        ? 'badge badge-high'
                                        : d.severity === 'MEDIUM'
                                          ? 'badge badge-medium'
                                          : 'badge badge-info'
                                }
                            >
                                {d.indicator}
                            </span>
                            {d.note && <span className="ml-2 text-slate-600">{d.note}</span>}
                        </li>
                    ))}
                </ul>
            )}
        </Panel>
    );
}

// ───────────────────────────────────────────────────────── Helpers ──

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <section className="bg-white rounded-lg shadow-sm p-4">
            <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide mb-3">
                {title}
            </h2>
            {children}
        </section>
    );
}

function DtDd({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <>
            <dt className="text-slate-500">{label}</dt>
            <dd>{children}</dd>
        </>
    );
}

function mergedSummary(detail: CaseDetail | null): CaseSummary | null {
    if (!detail) return null;
    if (detail.summary) return detail.summary;
    if (!detail.caseId || !detail.status) return null;
    return {
        caseId: detail.caseId,
        status: detail.status,
        score: detail.score ?? 0,
        scoreBand: detail.scoreBand,
        amount: detail.amount ?? '0',
        currency: detail.currency ?? '',
        holderNameMasked: detail.holderNameMasked,
        debtorAccountId: detail.debtorAccountId,
        creditorIbanMasked: detail.creditorIbanMasked,
        triggeredRules: detail.triggeredRules,
        assignee: detail.assignee,
        createdAt: detail.createdAt ?? new Date().toISOString(),
        slaDeadline: detail.slaDeadline ?? new Date().toISOString(),
        slaBreached: detail.slaBreached,
        paymentId: detail.paymentId,
    };
}

function bandFor(score: number): 'LOW' | 'MEDIUM' | 'HIGH' {
    if (!Number.isFinite(score)) return 'LOW';
    if (score >= 0.85) return 'HIGH';
    if (score >= 0.6) return 'MEDIUM';
    return 'LOW';
}

function scoreClass(band: 'LOW' | 'MEDIUM' | 'HIGH'): string {
    const base = 'inline-block tabular-nums px-2 py-0.5 rounded text-sm font-semibold ';
    if (band === 'HIGH') return base + 'bg-score-high text-white';
    if (band === 'MEDIUM') return base + 'bg-score-medium text-slate-900';
    return base + 'bg-score-low text-white';
}

function statusBadgeClass(s: Status): string {
    if (s === 'CONFIRMED_FRAUD') return 'badge badge-high';
    if (s === 'DISMISSED') return 'badge badge-neutral';
    if (s === 'ASSIGNED') return 'badge badge-info';
    return 'badge badge-medium';
}

function formatAmount(amount: string, currency: string, locale: string): string {
    const n = Number(amount);
    if (!Number.isFinite(n)) return `${amount} ${currency}`;
    try {
        return new Intl.NumberFormat(locale, {
            style: 'currency',
            currency,
            currencyDisplay: 'code',
        }).format(n);
    } catch {
        return `${amount} ${currency}`;
    }
}

function formatDateTime(iso: string, locale: string): string {
    try {
        return new Intl.DateTimeFormat(locale, {
            dateStyle: 'short',
            timeStyle: 'short',
        }).format(new Date(iso));
    } catch {
        return iso;
    }
}

function isPast(iso: string): boolean {
    const t = Date.parse(iso);
    return Number.isFinite(t) && t < Date.now();
}
