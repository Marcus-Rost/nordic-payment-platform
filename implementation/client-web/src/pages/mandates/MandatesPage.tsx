// UC-G08 / UC-G09 — AvtaleGiro mandate management.
//
// Lists existing mandates with status badges. Three actions:
//   - Setup new mandate (creditor + cap + start date)
//   - Amend existing (cap change)
//   - Terminate
// Each mutation runs UC-G51 SCA with class `mandate-action` and the
// matching `action` value (CREATE / AMEND / TERMINATE).

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { mandateActionDlHash } from '../../sca/dlHash';
import { formatAmount, formatDate } from '../../lib/format';

type MandateStatus = 'ACTIVE' | 'SUSPENDED' | 'CANCELLED' | 'PENDING_SCA';

interface Mandate {
    mandateId: string;
    creditorId: string;
    creditorName: string;
    debtorAccountId: string;
    capAmount?: string;
    capCurrency?: string;
    frequency?: 'MONTHLY' | 'WEEKLY' | 'AD_HOC';
    startDate?: string;
    nextDebitDate?: string;
    status: MandateStatus;
}

interface AccountSummary {
    accountId: string;
    nickname?: string;
    type: string;
    currency: string;
    iban?: string;
    ibanMasked?: string;
}

export function MandatesPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [mandates, setMandates] = useState<Mandate[] | null>(null);
    const [accounts, setAccounts] = useState<AccountSummary[]>([]);
    const [refresh, setRefresh] = useState(0);
    const [createOpen, setCreateOpen] = useState(false);
    const [amendOf, setAmendOf] = useState<Mandate | null>(null);
    const [terminateOf, setTerminateOf] = useState<Mandate | null>(null);

    useEffect(() => {
        api<{ items: Mandate[] }>('/api/v1/customer/mandates')
            .then((r) => setMandates(r.items ?? []))
            .catch((e) => showProblem(e));
        api<{ items: AccountSummary[] }>('/api/v1/customer/accounts')
            .then((r) => setAccounts(r.items ?? []))
            .catch(() => setAccounts([]));
    }, [refresh, showProblem]);

    return (
        <section aria-labelledby="mandates-title">
            <header className="flex items-baseline justify-between flex-wrap gap-3">
                <h1 id="mandates-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('mandates.title')}
                </h1>
                <button className="btn-primary" onClick={() => setCreateOpen(true)}>
                    {t('mandates.newMandate')}
                </button>
            </header>

            <div className="card mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('mandates.creditor')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('mandates.cap')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('mandates.frequency')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('mandates.startDate')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('accounts.status')}
                            </th>
                            <th scope="col" className="px-3 py-2 text-right">
                                {t('common.actions')}
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {mandates === null && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {mandates && mandates.length === 0 && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.noResults')}
                                </td>
                            </tr>
                        )}
                        {mandates?.map((m) => (
                            <tr key={m.mandateId} className="border-t border-slate-100">
                                <td className="px-3 py-2">
                                    <p>{m.creditorName}</p>
                                    <p className="text-xs text-slate-500 font-mono">
                                        {m.creditorId}
                                    </p>
                                </td>
                                <td className="px-3 py-2 tabular-nums">
                                    {m.capAmount && m.capCurrency
                                        ? formatAmount(m.capAmount, m.capCurrency, i18n.language)
                                        : '—'}
                                </td>
                                <td className="px-3 py-2">{m.frequency ?? '—'}</td>
                                <td className="px-3 py-2">
                                    {m.startDate ? formatDate(m.startDate, i18n.language) : '—'}
                                </td>
                                <td className="px-3 py-2">
                                    <span className={statusClass(m.status)}>
                                        {t(`mandates.${m.status === 'ACTIVE' ? 'active' : m.status === 'SUSPENDED' ? 'suspended' : 'cancelled'}`, {
                                            defaultValue: m.status,
                                        })}
                                    </span>
                                </td>
                                <td className="px-3 py-2 text-right">
                                    <div className="flex justify-end gap-1">
                                        {m.status === 'ACTIVE' && (
                                            <>
                                                <button
                                                    className="btn-secondary text-xs py-1"
                                                    onClick={() => setAmendOf(m)}
                                                >
                                                    {t('mandates.amend')}
                                                </button>
                                                <button
                                                    className="btn-danger text-xs py-1"
                                                    onClick={() => setTerminateOf(m)}
                                                >
                                                    {t('mandates.terminate')}
                                                </button>
                                            </>
                                        )}
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {createOpen && (
                <CreateMandateModal
                    accounts={accounts}
                    onClose={() => setCreateOpen(false)}
                    onDone={() => {
                        setCreateOpen(false);
                        setRefresh((n) => n + 1);
                        showSuccess(t('mandates.newMandate'));
                    }}
                />
            )}
            {amendOf && (
                <AmendMandateModal
                    mandate={amendOf}
                    onClose={() => setAmendOf(null)}
                    onDone={() => {
                        setAmendOf(null);
                        setRefresh((n) => n + 1);
                        showSuccess(t('mandates.amend'));
                    }}
                />
            )}
            {terminateOf && (
                <TerminateMandateModal
                    mandate={terminateOf}
                    onClose={() => setTerminateOf(null)}
                    onDone={() => {
                        setTerminateOf(null);
                        setRefresh((n) => n + 1);
                        showSuccess(t('mandates.terminate'));
                    }}
                />
            )}
        </section>
    );
}

function CreateMandateModal({
    accounts,
    onClose,
    onDone,
}: {
    accounts: AccountSummary[];
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [creditorId, setCreditorId] = useState('');
    const [debtorAccountId, setDebtorAccountId] = useState(accounts[0]?.accountId ?? '');
    const [capAmount, setCapAmount] = useState('');
    const [frequency, setFrequency] = useState<'MONTHLY' | 'WEEKLY' | 'AD_HOC'>('MONTHLY');
    const [startDate, setStartDate] = useState(new Date().toISOString().slice(0, 10));
    const [scaOpen, setScaOpen] = useState(false);
    const [hex, setHex] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const debtor = accounts.find((a) => a.accountId === debtorAccountId);
    const valid = !!creditorId && !!debtorAccountId && !!capAmount && Number(capAmount) > 0;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid || !debtor) return;
        setSubmitting(true);
        try {
            const { hex: h } = await mandateActionDlHash({
                action: 'CREATE',
                creditorId,
                debtorAccountId,
                capAmount,
                capCurrency: debtor.currency,
            });
            setHex(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        if (!debtor) return;
        try {
            await api('/api/v1/customer/mandates', {
                method: 'POST',
                stepUpToken,
                body: {
                    creditorId,
                    debtorAccountId,
                    capAmount,
                    capCurrency: debtor.currency,
                    frequency,
                    startDate,
                },
            });
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

    if (scaOpen && hex) {
        return (
            <ScaModal
                inputs={{
                    class: 'mandate-action',
                    action: 'CREATE',
                    creditorId,
                    debtorAccountId,
                    capAmount,
                    capCurrency: debtor?.currency,
                    notesHash: hex,
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
        <Modal titleId="mandate-create-title" onClose={onClose}>
            <h2 id="mandate-create-title" className="text-lg font-semibold text-jrpay-navy">
                {t('mandates.newMandate')}
            </h2>
            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <label className="block">
                    {t('mandates.creditor')}
                    <input
                        className="input mt-1"
                        value={creditorId}
                        onChange={(e) => setCreditorId(e.target.value)}
                        placeholder="creditor:NO123456"
                        required
                    />
                </label>
                <label className="block">
                    {t('payments.fromAccount')}
                    <select
                        className="input mt-1"
                        value={debtorAccountId}
                        onChange={(e) => setDebtorAccountId(e.target.value)}
                        required
                    >
                        {accounts.map((a) => (
                            <option key={a.accountId} value={a.accountId}>
                                {a.nickname ?? a.type} ({a.currency})
                            </option>
                        ))}
                    </select>
                </label>
                <label className="block">
                    {t('mandates.cap')}
                    <input
                        type="number"
                        step="0.01"
                        min="0.01"
                        className="input mt-1 tabular-nums"
                        value={capAmount}
                        onChange={(e) => setCapAmount(e.target.value)}
                        required
                    />
                </label>
                <label className="block">
                    {t('mandates.frequency')}
                    <select
                        className="input mt-1"
                        value={frequency}
                        onChange={(e) =>
                            setFrequency(e.target.value as 'MONTHLY' | 'WEEKLY' | 'AD_HOC')
                        }
                    >
                        <option value="MONTHLY">MONTHLY</option>
                        <option value="WEEKLY">WEEKLY</option>
                        <option value="AD_HOC">AD_HOC</option>
                    </select>
                </label>
                <label className="block">
                    {t('mandates.startDate')}
                    <input
                        type="date"
                        className="input mt-1"
                        value={startDate}
                        onChange={(e) => setStartDate(e.target.value)}
                        required
                    />
                </label>
                <div className="flex justify-end gap-2 mt-2">
                    <button
                        type="button"
                        className="btn-secondary"
                        onClick={onClose}
                        disabled={submitting}
                    >
                        {t('common.cancel')}
                    </button>
                    <button
                        type="submit"
                        className="btn-primary"
                        disabled={!valid || submitting}
                    >
                        {t('common.confirm')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}

function AmendMandateModal({
    mandate,
    onClose,
    onDone,
}: {
    mandate: Mandate;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [capAmount, setCapAmount] = useState(mandate.capAmount ?? '');
    const [scaOpen, setScaOpen] = useState(false);
    const [hex, setHex] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!capAmount || Number(capAmount) <= 0) return;
        setSubmitting(true);
        try {
            const { hex: h } = await mandateActionDlHash({
                action: 'AMEND',
                creditorId: mandate.creditorId,
                debtorAccountId: mandate.debtorAccountId,
                capAmount,
                capCurrency: mandate.capCurrency,
            });
            setHex(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            await api(`/api/v1/customer/mandates/${encodeURIComponent(mandate.mandateId)}`, {
                method: 'PATCH',
                stepUpToken,
                body: { capAmount },
            });
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

    if (scaOpen && hex) {
        return (
            <ScaModal
                inputs={{
                    class: 'mandate-action',
                    action: 'AMEND',
                    creditorId: mandate.creditorId,
                    debtorAccountId: mandate.debtorAccountId,
                    capAmount,
                    capCurrency: mandate.capCurrency,
                    notesHash: hex,
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
        <Modal titleId="mandate-amend-title" onClose={onClose}>
            <h2 id="mandate-amend-title" className="text-lg font-semibold text-jrpay-navy">
                {t('mandates.amend')} — {mandate.creditorName}
            </h2>
            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <label className="block">
                    {t('mandates.cap')} ({mandate.capCurrency ?? 'NOK'})
                    <input
                        type="number"
                        step="0.01"
                        min="0.01"
                        className="input mt-1 tabular-nums"
                        value={capAmount}
                        onChange={(e) => setCapAmount(e.target.value)}
                        required
                    />
                </label>
                <div className="flex justify-end gap-2 mt-2">
                    <button
                        type="button"
                        className="btn-secondary"
                        onClick={onClose}
                        disabled={submitting}
                    >
                        {t('common.cancel')}
                    </button>
                    <button
                        type="submit"
                        className="btn-primary"
                        disabled={submitting || Number(capAmount) <= 0}
                    >
                        {t('common.save')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}

function TerminateMandateModal({
    mandate,
    onClose,
    onDone,
}: {
    mandate: Mandate;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [scaOpen, setScaOpen] = useState(false);
    const [hex, setHex] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function startSca() {
        setSubmitting(true);
        try {
            const { hex: h } = await mandateActionDlHash({
                action: 'TERMINATE',
                creditorId: mandate.creditorId,
                debtorAccountId: mandate.debtorAccountId,
            });
            setHex(h);
            setScaOpen(true);
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            await api(`/api/v1/customer/mandates/${encodeURIComponent(mandate.mandateId)}`, {
                method: 'DELETE',
                stepUpToken,
            });
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

    if (scaOpen && hex) {
        return (
            <ScaModal
                inputs={{
                    class: 'mandate-action',
                    action: 'TERMINATE',
                    creditorId: mandate.creditorId,
                    debtorAccountId: mandate.debtorAccountId,
                    notesHash: hex,
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
        <Modal titleId="mandate-term-title" onClose={onClose}>
            <h2 id="mandate-term-title" className="text-lg font-semibold text-jrpay-navy">
                {t('mandates.terminate')} — {mandate.creditorName}
            </h2>
            <p className="mt-3 text-sm text-slate-700">{t('mandates.terminateConfirm')}</p>
            <div className="flex justify-end gap-2 mt-4">
                <button className="btn-secondary" onClick={onClose} disabled={submitting}>
                    {t('common.cancel')}
                </button>
                <button className="btn-danger" onClick={startSca} disabled={submitting}>
                    {t('mandates.terminate')}
                </button>
            </div>
        </Modal>
    );
}

function statusClass(s: MandateStatus): string {
    if (s === 'ACTIVE') return 'badge badge-success';
    if (s === 'SUSPENDED') return 'badge badge-warn';
    if (s === 'CANCELLED') return 'badge badge-neutral';
    return 'badge badge-info';
}
