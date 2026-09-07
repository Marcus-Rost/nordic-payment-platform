// UC-G34 §6.2 / §6.3 / §6.4 / §6.5 — sanctions hit detail + decide.
//
// Three decision outcomes:
//   - CONFIRMED_SANCTION → freezes account, rejects payment, queues FIU stub.
//   - FALSE_POSITIVE → optional FP-cache add (90-day TTL), payment released.
//   - RELEASE_FOR_FRAUD_REVIEW → spawns a fraud case, hit closed.
//
// SCA dynamic-linking input: class `admin-action`, action `DECIDE_SANCTIONS`,
// resourceType `hit`, resourceId <hitId>, decision, notesHash.
// Notes ≥ 50 chars (server-enforced).

import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

type Decision = 'CONFIRMED_SANCTION' | 'FALSE_POSITIVE' | 'RELEASE_FOR_FRAUD_REVIEW';

interface HitDetail {
    hitId: string;
    status: string;
    matchedEntityName: string;
    matchedList: string;
    score: number | string;
    assignee?: string | null;
    createdAt: string;
    slaDeadline: string;
    slaBreached?: boolean;
    paymentId?: string;
    debtorIbanMasked?: string;
    beneficiaryNameOnTransaction?: string;
    transaction?: {
        paymentId: string;
        amount: string;
        currency: string;
        reference?: string;
        creditorIban: string;
        creditorName: string;
        initiatedAt?: string;
    };
    matchedEntity?: {
        name: string;
        aliases?: string[];
        dob?: string;
        country?: string;
        listId: string;
        listingDate?: string;
        regulator?: string;
        resolutionRef?: string;
        legalBasisExcerpt?: string;
    };
    scoreBreakdown?: {
        matchedAliases?: { alias: string; score: number }[];
        threshold?: number;
    };
    priorDealings?: {
        transactionId: string;
        at: string;
        amount: string;
        currency: string;
        outcome: string;
    }[];
    decision?: {
        decision?: Decision;
        decidedBy?: string;
        decidedAt?: string;
        notesSummary?: string;
        notesHash?: string;
        fpCacheEntryId?: string;
        fraudCaseId?: string;
    };
}

export function SanctionsHitDetailPage() {
    const { t, i18n } = useTranslation();
    const { hitId = '' } = useParams<{ hitId: string }>();
    const { showProblem } = useToasts();

    const [detail, setDetail] = useState<HitDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [decideOpen, setDecideOpen] = useState(false);

    const reload = useCallback(() => {
        setLoading(true);
        api<HitDetail>(`/api/v1/admin/sanctions/hits/${encodeURIComponent(hitId)}`)
            .then(setDetail)
            .catch((e) => showProblem(e))
            .finally(() => setLoading(false));
    }, [hitId, showProblem]);

    useEffect(() => {
        reload();
    }, [reload]);

    const isDecidable = detail?.status === 'OPEN';
    const score = typeof detail?.score === 'string' ? Number(detail.score) : detail?.score ?? 0;

    return (
        <section aria-labelledby="hit-title">
            <Link to="/sanctions" className="text-sm text-jrpay-navy hover:underline">
                ← {t('sanctions.queueTitle')}
            </Link>

            {loading && !detail && <p className="mt-4 text-slate-500">{t('common.loading')}</p>}

            {detail && (
                <>
                    <header className="mt-2 flex flex-wrap items-baseline justify-between gap-3">
                        <div>
                            <h1
                                id="hit-title"
                                className="text-2xl font-semibold text-jrpay-navy"
                            >
                                {detail.matchedEntityName}{' '}
                                <span className="font-mono text-base text-slate-500">
                                    {detail.hitId}
                                </span>
                            </h1>
                            <p className="text-sm text-slate-600 mt-1">
                                <span className={statusClass(detail.status)}>{detail.status}</span>
                                <span className={`ml-2 ${scoreClass(score)}`}>
                                    {Number.isFinite(score) ? score.toFixed(2) : '—'}
                                </span>
                                <span className="ml-2 font-mono text-xs">
                                    {detail.matchedList}
                                </span>
                            </p>
                        </div>
                        {isDecidable && (
                            <button className="btn-primary" onClick={() => setDecideOpen(true)}>
                                {t('sanctions.decideTitle')}
                            </button>
                        )}
                    </header>

                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-4">
                        <div className="lg:col-span-2 space-y-4">
                            {detail.transaction && (
                                <Panel title="Transaction">
                                    <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                        <DtDd label="Payment id">
                                            <code className="text-xs">{detail.transaction.paymentId}</code>
                                        </DtDd>
                                        <DtDd label="Amount">
                                            <span className="tabular-nums">
                                                {formatAmount(
                                                    detail.transaction.amount,
                                                    detail.transaction.currency,
                                                    i18n.language
                                                )}
                                            </span>
                                        </DtDd>
                                        <DtDd label="Creditor IBAN">
                                            <code className="text-xs">
                                                {detail.transaction.creditorIban}
                                            </code>
                                        </DtDd>
                                        <DtDd label="Creditor name">
                                            {detail.transaction.creditorName}
                                        </DtDd>
                                        {detail.transaction.reference && (
                                            <DtDd label="Reference">{detail.transaction.reference}</DtDd>
                                        )}
                                        {detail.transaction.initiatedAt && (
                                            <DtDd label="Initiated">
                                                {formatDateTime(
                                                    detail.transaction.initiatedAt,
                                                    i18n.language
                                                )}
                                            </DtDd>
                                        )}
                                    </dl>
                                </Panel>
                            )}

                            {detail.matchedEntity && (
                                <Panel title="Matched entity">
                                    <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                        <DtDd label="Name">{detail.matchedEntity.name}</DtDd>
                                        <DtDd label="List">{detail.matchedEntity.listId}</DtDd>
                                        {detail.matchedEntity.regulator && (
                                            <DtDd label="Regulator">
                                                {detail.matchedEntity.regulator}
                                            </DtDd>
                                        )}
                                        {detail.matchedEntity.dob && (
                                            <DtDd label="DOB">{detail.matchedEntity.dob}</DtDd>
                                        )}
                                        {detail.matchedEntity.country && (
                                            <DtDd label="Country">
                                                {detail.matchedEntity.country}
                                            </DtDd>
                                        )}
                                        {detail.matchedEntity.listingDate && (
                                            <DtDd label="Listed">
                                                {detail.matchedEntity.listingDate}
                                            </DtDd>
                                        )}
                                        {detail.matchedEntity.resolutionRef && (
                                            <DtDd label="Resolution">
                                                {detail.matchedEntity.resolutionRef}
                                            </DtDd>
                                        )}
                                    </dl>
                                    {detail.matchedEntity.aliases &&
                                        detail.matchedEntity.aliases.length > 0 && (
                                            <div className="mt-3">
                                                <p className="text-xs text-slate-500 mb-1">Aliases</p>
                                                <div className="flex flex-wrap gap-1">
                                                    {detail.matchedEntity.aliases.map((a) => (
                                                        <span
                                                            key={a}
                                                            className="badge badge-neutral"
                                                        >
                                                            {a}
                                                        </span>
                                                    ))}
                                                </div>
                                            </div>
                                        )}
                                    {detail.matchedEntity.legalBasisExcerpt && (
                                        <div className="mt-3">
                                            <p className="text-xs text-slate-500 mb-1">
                                                Legal basis (excerpt)
                                            </p>
                                            <p className="text-sm whitespace-pre-wrap bg-slate-50 p-2 rounded">
                                                {detail.matchedEntity.legalBasisExcerpt}
                                            </p>
                                        </div>
                                    )}
                                </Panel>
                            )}

                            {detail.scoreBreakdown && (
                                <Panel title="Score breakdown">
                                    {detail.scoreBreakdown.threshold != null && (
                                        <p className="text-xs text-slate-500 mb-2">
                                            Threshold: {detail.scoreBreakdown.threshold}
                                        </p>
                                    )}
                                    <ul className="text-sm space-y-1">
                                        {(detail.scoreBreakdown.matchedAliases ?? []).map(
                                            (m, i) => (
                                                <li key={i} className="flex justify-between">
                                                    <span>{m.alias}</span>
                                                    <span className="tabular-nums">
                                                        {m.score.toFixed(2)}
                                                    </span>
                                                </li>
                                            )
                                        )}
                                    </ul>
                                </Panel>
                            )}

                            {detail.priorDealings && detail.priorDealings.length > 0 && (
                                <Panel title="Prior dealings">
                                    <table className="w-full text-sm">
                                        <thead className="text-slate-700">
                                            <tr>
                                                <th scope="col" className="text-left py-1">
                                                    When
                                                </th>
                                                <th scope="col" className="text-left py-1">
                                                    Amount
                                                </th>
                                                <th scope="col" className="text-left py-1">
                                                    Outcome
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {detail.priorDealings.map((p) => (
                                                <tr
                                                    key={p.transactionId}
                                                    className="border-t border-slate-100"
                                                >
                                                    <td className="py-1">
                                                        {formatDateTime(p.at, i18n.language)}
                                                    </td>
                                                    <td className="py-1 tabular-nums">
                                                        {formatAmount(
                                                            p.amount,
                                                            p.currency,
                                                            i18n.language
                                                        )}
                                                    </td>
                                                    <td className="py-1">{p.outcome}</td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </Panel>
                            )}

                            {detail.decision && (
                                <Panel title="Decision">
                                    <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                        <DtDd label="Outcome">
                                            <span className={statusClass(detail.decision.decision ?? '')}>
                                                {detail.decision.decision}
                                            </span>
                                        </DtDd>
                                        <DtDd label="By">{detail.decision.decidedBy ?? '—'}</DtDd>
                                        {detail.decision.decidedAt && (
                                            <DtDd label="At">
                                                {formatDateTime(
                                                    detail.decision.decidedAt,
                                                    i18n.language
                                                )}
                                            </DtDd>
                                        )}
                                        {detail.decision.notesSummary && (
                                            <DtDd label="Notes">
                                                <p className="whitespace-pre-wrap">
                                                    {detail.decision.notesSummary}
                                                </p>
                                            </DtDd>
                                        )}
                                        {detail.decision.fpCacheEntryId && (
                                            <DtDd label="FP cache">
                                                <code className="text-xs">
                                                    {detail.decision.fpCacheEntryId}
                                                </code>
                                            </DtDd>
                                        )}
                                        {detail.decision.fraudCaseId && (
                                            <DtDd label="Fraud case">
                                                <Link
                                                    to={`/fraud/${encodeURIComponent(detail.decision.fraudCaseId)}`}
                                                    className="text-jrpay-navy hover:underline"
                                                >
                                                    {detail.decision.fraudCaseId}
                                                </Link>
                                            </DtDd>
                                        )}
                                    </dl>
                                </Panel>
                            )}
                        </div>

                        <div className="space-y-4">
                            <Panel title="SLA">
                                <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                    <DtDd label="Created">
                                        {formatDateTime(detail.createdAt, i18n.language)}
                                    </DtDd>
                                    <DtDd label="Deadline">
                                        {formatDateTime(detail.slaDeadline, i18n.language)}
                                    </DtDd>
                                    <DtDd label="Status">
                                        {(detail.slaBreached === true ||
                                            Date.parse(detail.slaDeadline) < Date.now()) ? (
                                            <span className="badge badge-high">Breached</span>
                                        ) : (
                                            <span className="badge badge-low">Within SLA</span>
                                        )}
                                    </DtDd>
                                </dl>
                            </Panel>
                        </div>
                    </div>
                </>
            )}

            {decideOpen && detail && (
                <SanctionsDecideModal
                    hitId={detail.hitId}
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

function SanctionsDecideModal({
    hitId,
    onClose,
    onDecided,
}: {
    hitId: string;
    onClose: () => void;
    onDecided: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [decision, setDecision] = useState<Decision | ''>('');
    const [notes, setNotes] = useState('');
    const [confirmedCriteria, setConfirmedCriteria] = useState(false);
    const [addToFpCache, setAddToFpCache] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [notesHash, setNotesHash] = useState<string | null>(null);

    const notesValid = notes.length >= 50;
    const canSubmit = decision !== '' && notesValid && confirmedCriteria;

    async function startScaFlow() {
        if (!canSubmit) return;
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(notes);
            setNotesHash(h);
            setScaOpen(true);
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    async function postDecision(stepUpToken: string) {
        if (decision === '') return;
        try {
            const body: Record<string, unknown> = {
                decision,
                notes,
                confirmedCriteria: true,
            };
            if (decision === 'FALSE_POSITIVE') body.addToFpCache = addToFpCache;

            await api(`/api/v1/admin/sanctions/hits/${encodeURIComponent(hitId)}/decide`, {
                method: 'POST',
                stepUpToken,
                body,
            });
            showInfo(t(`sanctions.outcome${decision === 'CONFIRMED_SANCTION' ? 'Confirmed' : decision === 'FALSE_POSITIVE' ? 'FalsePositive' : 'ReleaseForFraud'}`));
            onDecided();
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
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
                <Modal titleId="san-decide-title" onClose={onClose}>
                    <h2 id="san-decide-title" className="text-lg font-semibold text-jrpay-navy">
                        {t('sanctions.decideTitle')}
                    </h2>

                    <fieldset className="mt-4 space-y-2">
                        <legend className="sr-only">Decision</legend>
                        <DecisionRadio
                            id="d-confirmed"
                            label={t('sanctions.outcomeConfirmed')}
                            value="CONFIRMED_SANCTION"
                            current={decision}
                            onChange={setDecision}
                        />
                        <DecisionRadio
                            id="d-fp"
                            label={t('sanctions.outcomeFalsePositive')}
                            value="FALSE_POSITIVE"
                            current={decision}
                            onChange={setDecision}
                        />
                        <DecisionRadio
                            id="d-release"
                            label={t('sanctions.outcomeReleaseForFraud')}
                            value="RELEASE_FOR_FRAUD_REVIEW"
                            current={decision}
                            onChange={setDecision}
                        />
                    </fieldset>

                    {decision === 'FALSE_POSITIVE' && (
                        <label className="flex items-center gap-2 mt-3 text-sm cursor-pointer">
                            <input
                                type="checkbox"
                                checked={addToFpCache}
                                onChange={(e) => setAddToFpCache(e.target.checked)}
                            />
                            {t('sanctions.addToFpCache')}
                        </label>
                    )}

                    <label htmlFor="san-notes" className="block text-sm font-medium mt-4">
                        {t('fraud.decideNotesLabel')}
                    </label>
                    <textarea
                        id="san-notes"
                        className="input mt-1"
                        rows={5}
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                    />
                    <div className="flex justify-between text-xs mt-1">
                        <p className="text-slate-500">{t('fraud.decideNotesHint')}</p>
                        <p className={notesValid ? 'text-jrpay-green' : 'text-jrpay-red'}>
                            {notes.length} / 50
                        </p>
                    </div>

                    <label className="flex items-start gap-2 mt-4 text-sm cursor-pointer">
                        <input
                            type="checkbox"
                            checked={confirmedCriteria}
                            onChange={(e) => setConfirmedCriteria(e.target.checked)}
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

            {scaOpen && notesHash && decision !== '' && (
                <ScaModal
                    inputs={{
                        class: 'admin-action',
                        action: 'DECIDE_SANCTIONS',
                        resourceType: 'hit',
                        resourceId: hitId,
                        decision,
                        notesHash,
                    }}
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

function DecisionRadio({
    id,
    label,
    value,
    current,
    onChange,
}: {
    id: string;
    label: string;
    value: Decision;
    current: Decision | '';
    onChange: (v: Decision) => void;
}) {
    return (
        <label htmlFor={id} className="flex items-center gap-2 cursor-pointer">
            <input
                id={id}
                type="radio"
                name="san-decision"
                value={value}
                checked={current === value}
                onChange={() => onChange(value)}
            />
            <span className="font-medium">{label}</span>
        </label>
    );
}

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

function statusClass(s: string): string {
    if (s === 'CONFIRMED_SANCTION') return 'badge badge-high';
    if (s === 'FALSE_POSITIVE') return 'badge badge-low';
    if (s === 'RELEASE_FOR_FRAUD_REVIEW') return 'badge badge-medium';
    return 'badge badge-info';
}

function scoreClass(score: number): string {
    const base = 'inline-block tabular-nums px-2 py-0.5 rounded text-sm font-semibold ';
    if (!Number.isFinite(score)) return base + 'bg-slate-200 text-slate-700';
    if (score >= 0.95) return base + 'bg-score-high text-white';
    if (score >= 0.85) return base + 'bg-score-medium text-slate-900';
    return base + 'bg-score-low text-white';
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
