// UC-G05 / UC-G07 — initiate instant or scheduled payment with VoP.
//
// Flow:
//   1. operator picks debtor account, types creditor IBAN, amount, reference
//   2. on IBAN/name blur → POST /api/v1/customer/payments/verify-payee
//      (VoP). Status badge updates: MATCH / CLOSE_MATCH / MISMATCH /
//      NO_MATCH / UNAVAILABLE.
//   3. on submit:
//      - compute paymentInitiationDlHash
//      - run UC-G51 SCA (BankID-simulated)
//      - POST /api/v1/customer/payments with the resulting Step-Up-Token
//      - on 401 step-up-required → reopen SCA modal
//      - on success → toast + redirect back to dashboard
//
// The page also lists scheduled payments below the form with cancel
// functionality.

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { ScaModal } from '../../sca/ScaModal';
import { paymentInitiationDlHash } from '../../sca/dlHash';
import { formatAmount, formatDate } from '../../lib/format';

interface AccountSummary {
    accountId: string;
    iban?: string;
    ibanMasked?: string;
    nickname?: string;
    type: string;
    currency: string;
    availableBalance?: string;
    status: string;
}

type VopResult = 'MATCH' | 'CLOSE_MATCH' | 'MISMATCH' | 'NO_MATCH' | 'UNAVAILABLE';

interface VopResponse {
    result: VopResult;
    actualName?: string;
    actualNameMasked?: string;
}

interface ScheduledPayment {
    paymentId: string;
    debtorAccountId: string;
    creditorIban: string;
    creditorName: string;
    amount: string;
    currency: string;
    executionDate: string;
    reference?: string;
    status: 'SCHEDULED' | 'CANCELLED' | 'EXECUTED';
}

type ExecutionMode = 'INSTANT' | 'SCHEDULED';

export function PaymentsPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [accounts, setAccounts] = useState<AccountSummary[]>([]);
    const [scheduled, setScheduled] = useState<ScheduledPayment[]>([]);
    const [refreshTick, setRefreshTick] = useState(0);

    const [mode, setMode] = useState<ExecutionMode>('INSTANT');
    const [debtorAccountId, setDebtorAccountId] = useState<string>('');
    const [creditorIban, setCreditorIban] = useState('');
    const [creditorName, setCreditorName] = useState('');
    const [amount, setAmount] = useState('');
    const [reference, setReference] = useState('');
    const [executionDate, setExecutionDate] = useState('');

    const [vopResult, setVopResult] = useState<VopResponse | null>(null);
    const [vopLoading, setVopLoading] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
        api<{ items: AccountSummary[] }>('/api/v1/customer/accounts')
            .then((r) => {
                const items = r.items ?? [];
                setAccounts(items);
                if (items.length > 0 && !debtorAccountId) {
                    setDebtorAccountId(items[0]!.accountId);
                }
            })
            .catch((e) => showProblem(e));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [showProblem]);

    useEffect(() => {
        api<{ items: ScheduledPayment[] }>('/api/v1/customer/payments/scheduled')
            .then((r) => setScheduled(r.items ?? []))
            .catch(() => setScheduled([]));
    }, [refreshTick]);

    // Debounced VoP whenever IBAN + name + amount stabilise.
    useEffect(() => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
        if (!creditorIban || !creditorName || !amount) {
            setVopResult(null);
            return;
        }
        debounceRef.current = setTimeout(async () => {
            setVopLoading(true);
            try {
                const res = await api<VopResponse>('/api/v1/customer/payments/verify-payee', {
                    method: 'POST',
                    body: {
                        creditorIban: creditorIban.trim(),
                        creditorName: creditorName.trim(),
                    },
                });
                setVopResult(res);
            } catch (e) {
                if (e instanceof ProblemError && e.isVopUnavailable) {
                    setVopResult({ result: 'UNAVAILABLE' });
                } else {
                    setVopResult(null);
                }
            } finally {
                setVopLoading(false);
            }
        }, 400);
        return () => {
            if (debounceRef.current) clearTimeout(debounceRef.current);
        };
    }, [creditorIban, creditorName, amount]);

    const debtor = accounts.find((a) => a.accountId === debtorAccountId);
    const canSubmit =
        !!debtor &&
        !!creditorIban &&
        !!creditorName &&
        !!amount &&
        Number(amount) > 0 &&
        (mode === 'INSTANT' || !!executionDate) &&
        vopResult?.result !== 'MISMATCH';

    async function startSca(e: React.FormEvent) {
        e.preventDefault();
        if (!canSubmit || !debtor) return;
        setSubmitting(true);
        try {
            const { hex } = await paymentInitiationDlHash({
                creditorIban: creditorIban.trim(),
                debtorAccountId: debtor.accountId,
                amount,
                currency: debtor.currency,
                reference: reference || undefined,
                executionDate: mode === 'SCHEDULED' ? executionDate : undefined,
            });
            setPendingHash(hex);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function postPayment(stepUpToken: string) {
        if (!debtor) return;
        try {
            await api('/api/v1/customer/payments', {
                method: 'POST',
                stepUpToken,
                body: {
                    debtorAccountId: debtor.accountId,
                    creditorIban: creditorIban.trim(),
                    creditorName: creditorName.trim(),
                    amount,
                    currency: debtor.currency,
                    reference: reference || undefined,
                    executionMode: mode,
                    executionDate: mode === 'SCHEDULED' ? executionDate : undefined,
                },
            });
            showSuccess(
                mode === 'INSTANT' ? t('payments.submitInstant') : t('payments.submitScheduled')
            );
            setCreditorIban('');
            setCreditorName('');
            setAmount('');
            setReference('');
            setExecutionDate('');
            setVopResult(null);
            setRefreshTick((n) => n + 1);
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
                setScaOpen(true);
                setSubmitting(false);
                return;
            }
            showProblem(e);
        } finally {
            setSubmitting(false);
        }
    }

    async function cancelScheduled(p: ScheduledPayment) {
        try {
            await api(`/api/v1/customer/payments/scheduled/${encodeURIComponent(p.paymentId)}`, {
                method: 'DELETE',
            });
            showSuccess(t('common.delete'));
            setRefreshTick((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    if (scaOpen && pendingHash && debtor) {
        return (
            <ScaModal
                inputs={{
                    class: 'payment-initiation',
                    creditorIban: creditorIban.trim(),
                    debtorAccountId: debtor.accountId,
                    amount,
                    currency: debtor.currency,
                    reference: reference || undefined,
                    executionDate: mode === 'SCHEDULED' ? executionDate : undefined,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void postPayment(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <section aria-labelledby="payments-title">
            <h1 id="payments-title" className="text-2xl font-semibold text-jrpay-navy">
                {t('payments.title')}
            </h1>

            <div className="card p-4 mt-4">
                <fieldset className="flex gap-2">
                    <legend className="sr-only">Mode</legend>
                    <label
                        className={`px-3 py-1 rounded-full text-xs cursor-pointer border ${
                            mode === 'INSTANT'
                                ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                : 'bg-white text-slate-700 border-slate-300'
                        }`}
                    >
                        <input
                            type="radio"
                            name="mode"
                            value="INSTANT"
                            checked={mode === 'INSTANT'}
                            onChange={() => setMode('INSTANT')}
                            className="sr-only"
                        />
                        {t('payments.instant')}
                    </label>
                    <label
                        className={`px-3 py-1 rounded-full text-xs cursor-pointer border ${
                            mode === 'SCHEDULED'
                                ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                : 'bg-white text-slate-700 border-slate-300'
                        }`}
                    >
                        <input
                            type="radio"
                            name="mode"
                            value="SCHEDULED"
                            checked={mode === 'SCHEDULED'}
                            onChange={() => setMode('SCHEDULED')}
                            className="sr-only"
                        />
                        {t('payments.scheduled')}
                    </label>
                </fieldset>

                <form onSubmit={startSca} className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-4 text-sm">
                    <label className="sm:col-span-2">
                        {t('payments.fromAccount')}
                        <select
                            className="input mt-1"
                            value={debtorAccountId}
                            onChange={(e) => setDebtorAccountId(e.target.value)}
                            required
                        >
                            {accounts.map((a) => (
                                <option key={a.accountId} value={a.accountId}>
                                    {a.nickname ?? a.type} —{' '}
                                    {formatAmount(
                                        a.availableBalance ?? '0',
                                        a.currency,
                                        i18n.language
                                    )}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label>
                        {t('payments.creditorIban')}
                        <input
                            type="text"
                            inputMode="text"
                            className="input mt-1 font-mono"
                            value={creditorIban}
                            onChange={(e) => setCreditorIban(e.target.value.toUpperCase())}
                            required
                        />
                    </label>
                    <label>
                        {t('payments.creditorName')}
                        <input
                            type="text"
                            className="input mt-1"
                            value={creditorName}
                            onChange={(e) => setCreditorName(e.target.value)}
                            required
                        />
                    </label>
                    <label>
                        {t('payments.amount')} ({debtor?.currency ?? 'NOK'})
                        <input
                            type="number"
                            step="0.01"
                            min="0.01"
                            className="input mt-1 tabular-nums"
                            value={amount}
                            onChange={(e) => setAmount(e.target.value)}
                            required
                        />
                    </label>
                    <label>
                        {t('payments.reference')}
                        <input
                            type="text"
                            className="input mt-1"
                            value={reference}
                            onChange={(e) => setReference(e.target.value)}
                            maxLength={140}
                        />
                    </label>
                    {mode === 'SCHEDULED' && (
                        <label className="sm:col-span-2">
                            {t('payments.executionDate')}
                            <input
                                type="date"
                                className="input mt-1"
                                value={executionDate}
                                onChange={(e) => setExecutionDate(e.target.value)}
                                min={new Date().toISOString().slice(0, 10)}
                                required
                            />
                        </label>
                    )}

                    <div className="sm:col-span-2">
                        <VopBadge loading={vopLoading} result={vopResult} />
                    </div>

                    <div className="sm:col-span-2 flex justify-end">
                        <button
                            type="submit"
                            className="btn-primary"
                            disabled={!canSubmit || submitting}
                        >
                            {mode === 'INSTANT'
                                ? t('payments.submitInstant')
                                : t('payments.submitScheduled')}
                        </button>
                    </div>
                </form>
            </div>

            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700 mt-6 mb-2">
                {t('payments.scheduledList')}
            </h2>
            <div className="card overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('payments.executionDate')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('payments.creditorIban')}
                            </th>
                            <th scope="col" className="text-right px-3 py-2">
                                {t('accounts.amount')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('accounts.status')}
                            </th>
                            <th scope="col" />
                        </tr>
                    </thead>
                    <tbody>
                        {scheduled.length === 0 && (
                            <tr>
                                <td colSpan={5} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.noResults')}
                                </td>
                            </tr>
                        )}
                        {scheduled.map((p) => (
                            <tr key={p.paymentId} className="border-t border-slate-100">
                                <td className="px-3 py-2">
                                    {formatDate(p.executionDate, i18n.language)}
                                </td>
                                <td className="px-3 py-2 font-mono text-xs">
                                    <p>{p.creditorName}</p>
                                    <p className="text-slate-500">{p.creditorIban}</p>
                                </td>
                                <td className="px-3 py-2 text-right tabular-nums">
                                    {formatAmount(p.amount, p.currency, i18n.language)}
                                </td>
                                <td className="px-3 py-2">
                                    <span className="badge badge-info">{p.status}</span>
                                </td>
                                <td className="px-3 py-2 text-right">
                                    {p.status === 'SCHEDULED' && (
                                        <button
                                            className="btn-secondary text-xs py-1"
                                            onClick={() => cancelScheduled(p)}
                                        >
                                            {t('common.delete')}
                                        </button>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </section>
    );
}

function VopBadge({
    loading,
    result,
}: {
    loading: boolean;
    result: VopResponse | null;
}) {
    const { t } = useTranslation();
    if (loading) {
        return (
            <p className="text-xs text-slate-500" aria-live="polite">
                {t('payments.verifyOfPayee')}
            </p>
        );
    }
    if (!result) return null;
    if (result.result === 'MATCH') {
        return <p className="badge badge-success">{t('payments.vopMatch')}</p>;
    }
    if (result.result === 'CLOSE_MATCH') {
        return (
            <p className="badge badge-warn">
                {t('payments.vopClose')}{' '}
                {result.actualNameMasked && (
                    <span className="ml-1 font-mono">→ {result.actualNameMasked}</span>
                )}
            </p>
        );
    }
    if (result.result === 'UNAVAILABLE') {
        return <p className="badge badge-warn">{t('payments.vopUnavailable')}</p>;
    }
    return <p className="badge badge-danger">{t('payments.vopMismatch')}</p>;
}
