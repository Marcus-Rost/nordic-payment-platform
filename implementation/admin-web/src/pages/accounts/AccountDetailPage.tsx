// UC-G35 §6.2 / §6.3 / §6.4 / §6.5 + UC-G36 — admin account detail.
//
// Layout: header with status, holder mask, balances. Action buttons:
//   - Freeze (FRAUD_INVESTIGATION / SANCTIONS_HOLD / CUSTOMER_REQUEST / OTHER)
//   - Unfreeze (SUPERVISOR+ only — server enforces, UI shows the button to all
//     and renders the 403 if role is missing)
//   - Close (zero-balance + zero-reservations preconditions; server enforces)
//
// Each mutation runs UC-G51 SCA with class admin-action,
// action ∈ {FREEZE_ACCOUNT, UNFREEZE_ACCOUNT, CLOSE_ACCOUNT, REVEAL_PII},
// resourceType=account, resourceId=<accountId>.
//
// PII reveal panel (UC-G36):
//   - per-field "Reveal" buttons (holderName, address, fodselsnummer)
//   - on success: render value + countdown timer; auto-remask at 0.
//   - "+60 s" extension button (max 3 extensions). After 3, button disables.
//   - rate-limited 202 → render "Awaiting SUPERVISOR approval" with the ref.
//   - manual "remask now" releases early.

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

interface AccountDetail {
    accountId: string;
    iban?: string;
    ibanMasked: string;
    bic?: string;
    type: string;
    currency: string;
    status: 'OPEN' | 'FROZEN' | 'CLOSED';
    holderNameMasked: string;
    holderAddressMasked?: string;
    fodselsnummerMasked?: string;
    customerId?: string;
    bookedBalance?: string;
    availableBalance?: string;
    reservedAmount?: string;
    reservations?: { paymentId: string; amount: string; currency: string; createdAt: string }[];
    openedAt?: string;
    closedAt?: string;
    freezeReason?: string;
    freezeDetail?: {
        reason?: string;
        reference?: string;
        appliedAt?: string;
        appliedBy?: string;
        notesSummary?: string;
    };
}

type FreezeReason =
    | 'FRAUD_INVESTIGATION'
    | 'SANCTIONS_HOLD'
    | 'CUSTOMER_REQUEST'
    | 'OPERATIONAL'
    | 'OTHER';
// Mirrors the server-side `com.jrpay.ledger.domain.UnfreezeReason` enum.
// Drift caught during the 2026-04-27 GUI E2E: the previous string set
// (CUSTOMER_VERIFIED, CASE_DISMISSED, REGULATORY) didn't match the server
// and produced 500 Jackson deserialisation errors on /unfreeze.
type UnfreezeReason =
    | 'INVESTIGATION_CONCLUDED'
    | 'FALSE_POSITIVE_CONFIRMED'
    | 'CUSTOMER_DISPUTE_RESOLVED'
    | 'OTHER';
type CloseReason =
    | 'CUSTOMER_REQUEST'
    | 'GDPR_ERASURE'
    | 'OPERATIONAL_CLEANUP'
    | 'REGULATORY'
    | 'OTHER';
type PiiField = 'holderName' | 'address' | 'fodselsnummer';

export function AccountDetailPage() {
    const { t, i18n } = useTranslation();
    const { accountId = '' } = useParams<{ accountId: string }>();
    const { showProblem } = useToasts();

    const [detail, setDetail] = useState<AccountDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [openModal, setOpenModal] = useState<null | 'freeze' | 'unfreeze' | 'close'>(null);

    const reload = useCallback(() => {
        setLoading(true);
        api<AccountDetail>(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}`)
            .then(setDetail)
            .catch((e) => showProblem(e))
            .finally(() => setLoading(false));
    }, [accountId, showProblem]);

    useEffect(() => {
        reload();
    }, [reload]);

    return (
        <section aria-labelledby="account-title">
            <Link to="/accounts" className="text-sm text-jrpay-navy hover:underline">
                ← {t('accounts.searchTitle')}
            </Link>

            {loading && !detail && <p className="mt-4 text-slate-500">{t('common.loading')}</p>}

            {detail && (
                <>
                    <header className="mt-2 flex flex-wrap items-baseline justify-between gap-3">
                        <div>
                            <h1
                                id="account-title"
                                className="text-2xl font-semibold text-jrpay-navy"
                            >
                                {detail.holderNameMasked}
                            </h1>
                            <p className="text-sm text-slate-600 mt-1">
                                <span className={statusClass(detail.status)}>{detail.status}</span>
                                <span className="ml-2 font-mono text-xs">
                                    {detail.iban ?? detail.ibanMasked}
                                </span>
                            </p>
                        </div>
                        <div className="flex flex-wrap gap-2">
                            {detail.status === 'OPEN' && (
                                <>
                                    <button
                                        className="btn-secondary"
                                        onClick={() => setOpenModal('freeze')}
                                    >
                                        {t('accounts.freeze')}
                                    </button>
                                    <button
                                        className="btn-secondary"
                                        onClick={() => setOpenModal('close')}
                                    >
                                        {t('accounts.close')}
                                    </button>
                                </>
                            )}
                            {detail.status === 'FROZEN' && (
                                <button
                                    className="btn-secondary"
                                    onClick={() => setOpenModal('unfreeze')}
                                >
                                    {t('accounts.unfreeze')}
                                </button>
                            )}
                        </div>
                    </header>

                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-4">
                        <div className="lg:col-span-2 space-y-4">
                            <Panel title="Account">
                                <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                    <DtDd label="Type">{detail.type}</DtDd>
                                    <DtDd label="Currency">{detail.currency}</DtDd>
                                    <DtDd label="Booked">
                                        <span className="tabular-nums">
                                            {formatAmount(
                                                detail.bookedBalance ?? '0',
                                                detail.currency,
                                                i18n.language
                                            )}
                                        </span>
                                    </DtDd>
                                    <DtDd label="Available">
                                        <span className="tabular-nums">
                                            {formatAmount(
                                                detail.availableBalance ?? '0',
                                                detail.currency,
                                                i18n.language
                                            )}
                                        </span>
                                    </DtDd>
                                    <DtDd label="Reserved">
                                        <span className="tabular-nums">
                                            {formatAmount(
                                                detail.reservedAmount ?? '0',
                                                detail.currency,
                                                i18n.language
                                            )}
                                        </span>
                                    </DtDd>
                                    {detail.openedAt && (
                                        <DtDd label="Opened">
                                            {formatDateTime(detail.openedAt, i18n.language)}
                                        </DtDd>
                                    )}
                                    {detail.closedAt && (
                                        <DtDd label="Closed">
                                            {formatDateTime(detail.closedAt, i18n.language)}
                                        </DtDd>
                                    )}
                                </dl>
                            </Panel>

                            {detail.freezeDetail && (
                                <Panel title="Freeze details">
                                    <dl className="grid grid-cols-2 gap-y-2 text-sm">
                                        <DtDd label="Reason">{detail.freezeDetail.reason}</DtDd>
                                        {detail.freezeDetail.reference && (
                                            <DtDd label="Reference">
                                                {detail.freezeDetail.reference}
                                            </DtDd>
                                        )}
                                        {detail.freezeDetail.appliedAt && (
                                            <DtDd label="Applied">
                                                {formatDateTime(
                                                    detail.freezeDetail.appliedAt,
                                                    i18n.language
                                                )}
                                            </DtDd>
                                        )}
                                        {detail.freezeDetail.appliedBy && (
                                            <DtDd label="By">{detail.freezeDetail.appliedBy}</DtDd>
                                        )}
                                        {detail.freezeDetail.notesSummary && (
                                            <DtDd label="Notes">
                                                {detail.freezeDetail.notesSummary}
                                            </DtDd>
                                        )}
                                    </dl>
                                </Panel>
                            )}

                            {detail.reservations && detail.reservations.length > 0 && (
                                <Panel title="Reservations">
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
                                                    When
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {detail.reservations.map((r) => (
                                                <tr
                                                    key={r.paymentId}
                                                    className="border-t border-slate-100"
                                                >
                                                    <td className="py-1">
                                                        <code className="text-xs">
                                                            {r.paymentId}
                                                        </code>
                                                    </td>
                                                    <td className="py-1 tabular-nums">
                                                        {formatAmount(
                                                            r.amount,
                                                            r.currency,
                                                            i18n.language
                                                        )}
                                                    </td>
                                                    <td className="py-1">
                                                        {formatDateTime(r.createdAt, i18n.language)}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </Panel>
                            )}
                        </div>

                        <div className="space-y-4">
                            <PiiPanel
                                accountId={detail.accountId}
                                masks={{
                                    holderName: detail.holderNameMasked,
                                    address: detail.holderAddressMasked,
                                    fodselsnummer: detail.fodselsnummerMasked,
                                }}
                            />
                        </div>
                    </div>
                </>
            )}

            {openModal === 'freeze' && detail && (
                <FreezeModal
                    accountId={detail.accountId}
                    onClose={() => setOpenModal(null)}
                    onDone={() => {
                        setOpenModal(null);
                        reload();
                    }}
                />
            )}
            {openModal === 'unfreeze' && detail && (
                <UnfreezeModal
                    accountId={detail.accountId}
                    onClose={() => setOpenModal(null)}
                    onDone={() => {
                        setOpenModal(null);
                        reload();
                    }}
                />
            )}
            {openModal === 'close' && detail && (
                <CloseAccountModal
                    accountId={detail.accountId}
                    onClose={() => setOpenModal(null)}
                    onDone={() => {
                        setOpenModal(null);
                        reload();
                    }}
                />
            )}
        </section>
    );
}

// ─────────────────────────────────────────────── Mutation modals ──

function ActionModalScaffold({
    titleId,
    title,
    submit,
    canSubmit,
    children,
    onClose,
    submitting,
}: {
    titleId: string;
    title: string;
    submit: string;
    canSubmit: boolean;
    children: React.ReactNode;
    onClose: () => void;
    submitting: boolean;
}) {
    const { t } = useTranslation();
    return (
        <Modal titleId={titleId} onClose={onClose}>
            <h2 id={titleId} className="text-lg font-semibold text-jrpay-navy">
                {title}
            </h2>
            <div className="mt-4">{children}</div>
            <div className="mt-6 flex justify-end gap-2">
                <button className="btn-secondary" onClick={onClose} disabled={submitting}>
                    {t('common.cancel')}
                </button>
                <button
                    form="modal-form"
                    type="submit"
                    className="btn-primary"
                    disabled={!canSubmit || submitting}
                >
                    {submit}
                </button>
            </div>
        </Modal>
    );
}

function FreezeModal({
    accountId,
    onClose,
    onDone,
}: {
    accountId: string;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [reason, setReason] = useState<FreezeReason>('FRAUD_INVESTIGATION');
    const [freezeReference, setFreezeReference] = useState('');
    const [notes, setNotes] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const valid = notes.length >= 50 && confirmed;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(notes);
            setPendingHash(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function postFreeze(stepUpToken: string) {
        try {
            await api(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/freeze`, {
                method: 'POST',
                stepUpToken,
                body: {
                    reason,
                    notes,
                    confirmedCriteria: true,
                    freezeReference: freezeReference || undefined,
                },
            });
            showInfo(t('accounts.freeze'));
            onDone();
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

    if (scaOpen && pendingHash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'FREEZE_ACCOUNT',
                    resourceType: 'account',
                    resourceId: accountId,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void postFreeze(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <ActionModalScaffold
            titleId="freeze-title"
            title={t('accounts.freeze')}
            submit={t('fraud.decideSubmit')}
            canSubmit={valid}
            onClose={onClose}
            submitting={submitting}
        >
            <form id="modal-form" onSubmit={startSca} className="space-y-3 text-sm">
                <label className="block">
                    {t('accounts.freezeReason')}
                    <select
                        className="input mt-1"
                        value={reason}
                        onChange={(e) => setReason(e.target.value as FreezeReason)}
                    >
                        <option>FRAUD_INVESTIGATION</option>
                        <option>SANCTIONS_HOLD</option>
                        <option>CUSTOMER_REQUEST</option>
                        <option>OPERATIONAL</option>
                        <option>OTHER</option>
                    </select>
                </label>
                <label className="block">
                    {t('accounts.freezeReference')}
                    <input
                        className="input mt-1"
                        value={freezeReference}
                        onChange={(e) => setFreezeReference(e.target.value)}
                    />
                </label>
                <label className="block">
                    {t('accounts.freezeNotes')}
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                    />
                </label>
                <p className={notes.length >= 50 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                    {notes.length} / 50
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    <span>{t('accounts.confirmedCriteria')}</span>
                </label>
            </form>
        </ActionModalScaffold>
    );
}

function UnfreezeModal({
    accountId,
    onClose,
    onDone,
}: {
    accountId: string;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [reason, setReason] = useState<UnfreezeReason>('INVESTIGATION_CONCLUDED');
    const [notes, setNotes] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const valid = notes.length >= 50 && confirmed;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(notes);
            setPendingHash(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            await api(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/unfreeze`, {
                method: 'POST',
                stepUpToken,
                body: { reason, notes, confirmedCriteria: true },
            });
            showInfo(t('accounts.unfreeze'));
            onDone();
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

    if (scaOpen && pendingHash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'UNFREEZE_ACCOUNT',
                    resourceType: 'account',
                    resourceId: accountId,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void post(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <ActionModalScaffold
            titleId="unfreeze-title"
            title={t('accounts.unfreeze')}
            submit={t('fraud.decideSubmit')}
            canSubmit={valid}
            onClose={onClose}
            submitting={submitting}
        >
            <form id="modal-form" onSubmit={startSca} className="space-y-3 text-sm">
                <label className="block">
                    {t('accounts.freezeReason')}
                    <select
                        className="input mt-1"
                        value={reason}
                        onChange={(e) => setReason(e.target.value as UnfreezeReason)}
                    >
                        <option>INVESTIGATION_CONCLUDED</option>
                        <option>FALSE_POSITIVE_CONFIRMED</option>
                        <option>CUSTOMER_DISPUTE_RESOLVED</option>
                        <option>OTHER</option>
                    </select>
                </label>
                <label className="block">
                    {t('accounts.freezeNotes')}
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                    />
                </label>
                <p className={notes.length >= 50 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                    {notes.length} / 50
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    <span>{t('accounts.confirmedCriteria')}</span>
                </label>
            </form>
        </ActionModalScaffold>
    );
}

function CloseAccountModal({
    accountId,
    onClose,
    onDone,
}: {
    accountId: string;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [reason, setReason] = useState<CloseReason>('CUSTOMER_REQUEST');
    const [notes, setNotes] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const valid = notes.length >= 50 && confirmed;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(notes);
            setPendingHash(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            await api(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/close`, {
                method: 'POST',
                stepUpToken,
                body: { reason, notes, confirmedCriteria: true },
            });
            showInfo(t('accounts.close'));
            onDone();
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

    if (scaOpen && pendingHash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'CLOSE_ACCOUNT',
                    resourceType: 'account',
                    resourceId: accountId,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void post(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <ActionModalScaffold
            titleId="close-title"
            title={t('accounts.close')}
            submit={t('fraud.decideSubmit')}
            canSubmit={valid}
            onClose={onClose}
            submitting={submitting}
        >
            <form id="modal-form" onSubmit={startSca} className="space-y-3 text-sm">
                <label className="block">
                    {t('accounts.freezeReason')}
                    <select
                        className="input mt-1"
                        value={reason}
                        onChange={(e) => setReason(e.target.value as CloseReason)}
                    >
                        <option>CUSTOMER_REQUEST</option>
                        <option>GDPR_ERASURE</option>
                        <option>OPERATIONAL_CLEANUP</option>
                        <option>REGULATORY</option>
                        <option>OTHER</option>
                    </select>
                </label>
                <label className="block">
                    {t('accounts.freezeNotes')}
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                    />
                </label>
                <p className={notes.length >= 50 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                    {notes.length} / 50
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    <span>{t('accounts.confirmedCriteria')}</span>
                </label>
            </form>
        </ActionModalScaffold>
    );
}

// ─────────────────────────────────────────────── PII reveal panel ──

interface RevealedField {
    field: PiiField;
    value: string;
    revealId?: string;
    revealedAt: number; // local epoch ms
    ttlSeconds: number;
    extensionsRemaining: number;
}

function PiiPanel({
    accountId,
    masks,
}: {
    accountId: string;
    masks: { holderName: string; address?: string; fodselsnummer?: string };
}) {
    const { t } = useTranslation();
    const fields: { id: PiiField; mask: string | undefined }[] = [
        { id: 'holderName', mask: masks.holderName },
        { id: 'address', mask: masks.address },
        { id: 'fodselsnummer', mask: masks.fodselsnummer },
    ];

    return (
        <Panel title="PII">
            <ul className="space-y-3">
                {fields.map((f) =>
                    f.mask ? (
                        <PiiRow key={f.id} accountId={accountId} field={f.id} mask={f.mask} />
                    ) : null
                )}
                {!masks.fodselsnummer && (
                    <li className="text-xs text-slate-500">
                        Fødselsnummer reveal requires COMPLIANCE_OFFICER+
                    </li>
                )}
            </ul>
            <p className="text-xs text-slate-500 mt-3">{t('login.scaNotice')}</p>
        </Panel>
    );
}

function PiiRow({
    accountId,
    field,
    mask,
}: {
    accountId: string;
    field: PiiField;
    mask: string;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();

    const [revealed, setRevealed] = useState<RevealedField | null>(null);
    const [pendingApproval, setPendingApproval] = useState<{
        approvalRef: string;
        expiresAt: string;
    } | null>(null);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [pendingJustification, setPendingJustification] = useState('');
    const [askJustification, setAskJustification] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const tickRef = useRef<number>(0);

    // Tick once per second to redraw the countdown.
    const [, force] = useState(0);
    useEffect(() => {
        if (!revealed) return;
        tickRef.current = window.setInterval(() => force((n) => n + 1), 1_000);
        return () => clearInterval(tickRef.current);
    }, [revealed]);

    // Auto-remask when TTL hits 0.
    useEffect(() => {
        if (!revealed) return;
        const expiresAt = revealed.revealedAt + revealed.ttlSeconds * 1_000;
        if (Date.now() >= expiresAt) setRevealed(null);
    });

    async function beginReveal(justification: string) {
        try {
            const h = await sha256HexNfc(justification);
            setPendingHash(h);
            setPendingJustification(justification);
            setScaOpen(true);
        } catch (e) {
            showProblem(e);
        }
    }

    async function postReveal(stepUpToken: string) {
        setSubmitting(true);
        try {
            // Server returns either RevealPiiAck (200) or RevealPiiPendingApproval
            // (202). The api wrapper unwraps both into the JSON body; we
            // discriminate by the presence of `requiresSecondApproval`.
            const body = await api<
                | {
                      field: PiiField;
                      value: string;
                      revealId?: string;
                      ttlSeconds: number;
                      extensionsRemaining: number;
                      revealedAt?: string;
                  }
                | {
                      requiresSecondApproval: true;
                      approvalRef: string;
                      expiresAt: string;
                  }
            >(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/reveal-pii`, {
                method: 'POST',
                stepUpToken,
                body: {
                    field,
                    justificationNotes: pendingJustification,
                    confirmedBusinessNeed: true,
                },
            });
            if ('requiresSecondApproval' in body) {
                setPendingApproval({
                    approvalRef: body.approvalRef,
                    expiresAt: body.expiresAt,
                });
                return;
            }
            setRevealed({
                field: body.field,
                value: body.value,
                revealId: body.revealId,
                revealedAt: Date.now(),
                ttlSeconds: body.ttlSeconds ?? 60,
                extensionsRemaining: body.extensionsRemaining ?? 3,
            });
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
                setScaOpen(true);
                return;
            }
            showProblem(e);
        } finally {
            setSubmitting(false);
        }
    }

    async function extend() {
        if (!revealed?.revealId) return;
        try {
            const res = await api<{ ttlSeconds: number; extensionsRemaining: number }>(
                `/api/v1/admin/accounts/${encodeURIComponent(accountId)}/reveal-pii/extend`,
                {
                    method: 'POST',
                    body: { revealId: revealed.revealId },
                }
            );
            setRevealed({
                ...revealed,
                revealedAt: Date.now(),
                ttlSeconds: res.ttlSeconds,
                extensionsRemaining: res.extensionsRemaining,
            });
        } catch (e) {
            showProblem(e);
        }
    }

    function remask() {
        setRevealed(null);
        showInfo(t('pii.remask'));
    }

    if (scaOpen && pendingHash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'REVEAL_PII',
                    resourceType: 'account',
                    resourceId: accountId,
                    decision: field,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void postReveal(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    if (askJustification) {
        return (
            <li>
                <RevealJustificationForm
                    field={field}
                    onCancel={() => setAskJustification(false)}
                    onSubmit={(justification) => {
                        setAskJustification(false);
                        void beginReveal(justification);
                    }}
                />
            </li>
        );
    }

    const ttlLeft = revealed
        ? Math.max(0, Math.ceil((revealed.revealedAt + revealed.ttlSeconds * 1_000 - Date.now()) / 1_000))
        : 0;

    return (
        <li className="border-b last:border-b-0 border-slate-100 pb-2">
            <div className="flex items-center justify-between gap-2">
                <div>
                    <p className="text-xs text-slate-500 uppercase tracking-wide">{field}</p>
                    {revealed ? (
                        <p className="text-sm font-medium text-jrpay-navy">{revealed.value}</p>
                    ) : pendingApproval ? (
                        <p className="text-sm text-amber-700">
                            {t('pii.pendingApproval', { ref: pendingApproval.approvalRef })}
                        </p>
                    ) : (
                        <p className="text-sm">{mask}</p>
                    )}
                </div>
                {!revealed && !pendingApproval && (
                    <button
                        className="btn-secondary text-xs py-1"
                        onClick={() => setAskJustification(true)}
                        disabled={submitting}
                    >
                        {t('accounts.revealPii')}
                    </button>
                )}
            </div>
            {revealed && (
                <div className="mt-2 flex items-center gap-2">
                    <p
                        role="timer"
                        aria-live="polite"
                        className={`text-xs ${ttlLeft <= 10 ? 'text-jrpay-red' : 'text-slate-500'}`}
                    >
                        {t('pii.ttlRemaining', { seconds: ttlLeft })}
                    </p>
                    <button
                        className="btn-secondary text-xs py-1"
                        onClick={extend}
                        disabled={revealed.extensionsRemaining === 0}
                    >
                        {t('pii.extend')}
                    </button>
                    <button className="btn-secondary text-xs py-1" onClick={remask}>
                        {t('pii.remask')}
                    </button>
                    <p className="text-xs text-slate-500">
                        {t('pii.extensionsRemaining', { count: revealed.extensionsRemaining })}
                    </p>
                </div>
            )}
        </li>
    );
}

function RevealJustificationForm({
    field,
    onCancel,
    onSubmit,
}: {
    field: PiiField;
    onCancel: () => void;
    onSubmit: (justification: string) => void;
}) {
    const { t } = useTranslation();
    const [text, setText] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const valid = text.length >= 20 && confirmed;
    return (
        <div className="bg-slate-50 p-3 rounded-md">
            <p className="text-xs text-slate-500 uppercase tracking-wide">{field}</p>
            <label className="block text-sm mt-1">
                {t('pii.justificationLabel')}
                <textarea
                    className="input mt-1"
                    rows={3}
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                />
            </label>
            <p className={text.length >= 20 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                {text.length} / 20
            </p>
            <label className="flex items-start gap-2 mt-2 text-sm cursor-pointer">
                <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                />
                <span>{t('pii.confirmBusinessNeed')}</span>
            </label>
            <div className="mt-3 flex justify-end gap-2">
                <button className="btn-secondary text-xs py-1" onClick={onCancel}>
                    {t('common.cancel')}
                </button>
                <button
                    className="btn-primary text-xs py-1"
                    onClick={() => onSubmit(text)}
                    disabled={!valid}
                >
                    {t('accounts.revealPii')}
                </button>
            </div>
        </div>
    );
}

// ─────────────────────────────────────────────── Layout helpers ──

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
    if (s === 'FROZEN') return 'badge badge-medium';
    if (s === 'CLOSED') return 'badge badge-neutral';
    return 'badge badge-low';
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
