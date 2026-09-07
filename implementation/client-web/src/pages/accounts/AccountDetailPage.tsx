// UC-G03 — account detail with paginated transactions and CSV export.
// Filters: from / to / direction / minAmount / maxAmount.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { formatAmount, formatDateTime } from '../../lib/format';

interface AccountDetail {
    accountId: string;
    iban?: string;
    ibanMasked?: string;
    bic?: string;
    nickname?: string;
    type: string;
    currency: string;
    bookedBalance?: string;
    availableBalance?: string;
    reservedAmount?: string;
    status: string;
    openedAt?: string;
}

interface Transaction {
    transactionId: string;
    postedAt: string;
    direction: 'DEBIT' | 'CREDIT';
    amount: string;
    currency: string;
    status: string;
    counterpartyName?: string;
    counterpartyIban?: string;
    reference?: string;
}

interface PageMeta {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

const PAGE_SIZE = 25;

export function AccountDetailPage() {
    const { t, i18n } = useTranslation();
    const { accountId = '' } = useParams<{ accountId: string }>();
    const { showProblem, showSuccess } = useToasts();
    const [searchParams, setSearchParams] = useSearchParams();

    const from = searchParams.get('from') ?? '';
    const to = searchParams.get('to') ?? '';
    const direction = (searchParams.get('direction') ?? '') as '' | 'DEBIT' | 'CREDIT';
    const page = parsePage(searchParams.get('page'));

    const [detail, setDetail] = useState<AccountDetail | null>(null);
    const [items, setItems] = useState<Transaction[] | null>(null);
    const [pageMeta, setPageMeta] = useState<PageMeta | null>(null);

    const reload = useCallback(() => {
        api<AccountDetail>(`/api/v1/customer/accounts/${encodeURIComponent(accountId)}`)
            .then(setDetail)
            .catch((e) => showProblem(e));
        api<{ items: Transaction[]; page: PageMeta }>(
            `/api/v1/customer/accounts/${encodeURIComponent(accountId)}/transactions`,
            {
                query: {
                    from: from || undefined,
                    to: to || undefined,
                    direction: direction || undefined,
                    page,
                    size: PAGE_SIZE,
                },
            }
        )
            .then((r) => {
                setItems(r.items ?? []);
                setPageMeta(r.page);
            })
            .catch((e) => showProblem(e));
    }, [accountId, from, to, direction, page, showProblem]);

    useEffect(() => {
        reload();
    }, [reload]);

    function setParam(k: string, v: string | null) {
        const next = new URLSearchParams(searchParams);
        if (v === null || v === '') next.delete(k);
        else next.set(k, v);
        next.set('page', '0');
        setSearchParams(next);
    }

    async function exportStatement() {
        try {
            const res = await api<{ requestId: string }>(
                `/api/v1/customer/accounts/${encodeURIComponent(accountId)}/statement`,
                {
                    method: 'POST',
                    body: { format: 'CSV', from: from || undefined, to: to || undefined },
                }
            );
            showSuccess(t('accounts.exportStatement'), res.requestId);
        } catch (e) {
            showProblem(e);
        }
    }

    const totalCredits = useMemo(
        () =>
            (items ?? [])
                .filter((tx) => tx.direction === 'CREDIT')
                .reduce((s, tx) => s + (Number(tx.amount) || 0), 0),
        [items]
    );
    const totalDebits = useMemo(
        () =>
            (items ?? [])
                .filter((tx) => tx.direction === 'DEBIT')
                .reduce((s, tx) => s + (Number(tx.amount) || 0), 0),
        [items]
    );

    return (
        <section aria-labelledby="account-title">
            <Link to="/" className="text-sm text-jrpay-navy hover:underline">
                ← {t('common.back')}
            </Link>

            {!detail && <p className="mt-4 text-slate-500">{t('common.loading')}</p>}

            {detail && (
                <>
                    <header className="mt-2 flex flex-wrap items-baseline justify-between gap-3">
                        <div>
                            <h1
                                id="account-title"
                                className="text-2xl font-semibold text-jrpay-navy"
                            >
                                {detail.nickname ?? detail.type}
                            </h1>
                            <p className="font-mono text-sm text-slate-600 mt-1">
                                {detail.iban ?? detail.ibanMasked}
                            </p>
                        </div>
                        <div className="text-right">
                            <p className="text-xs uppercase tracking-wide text-slate-500">
                                {t('accounts.available')}
                            </p>
                            <p className="text-2xl font-semibold tabular-nums">
                                {formatAmount(
                                    detail.availableBalance ?? '0',
                                    detail.currency,
                                    i18n.language
                                )}
                            </p>
                            <p className="text-xs text-slate-500 mt-1">
                                {t('accounts.balance')}:{' '}
                                {formatAmount(
                                    detail.bookedBalance ?? '0',
                                    detail.currency,
                                    i18n.language
                                )}
                            </p>
                        </div>
                    </header>

                    <div className="card p-4 mt-4 flex flex-wrap items-end gap-3">
                        <label className="text-sm">
                            <span className="block text-slate-700 mb-1">
                                {t('accounts.from')}
                            </span>
                            <input
                                type="date"
                                className="input"
                                value={from}
                                onChange={(e) => setParam('from', e.target.value || null)}
                            />
                        </label>
                        <label className="text-sm">
                            <span className="block text-slate-700 mb-1">
                                {t('accounts.to')}
                            </span>
                            <input
                                type="date"
                                className="input"
                                value={to}
                                onChange={(e) => setParam('to', e.target.value || null)}
                            />
                        </label>
                        <label className="text-sm">
                            <span className="block text-slate-700 mb-1">
                                {t('accounts.status')}
                            </span>
                            <select
                                className="input"
                                value={direction}
                                onChange={(e) =>
                                    setParam('direction', e.target.value || null)
                                }
                            >
                                <option value="">—</option>
                                <option value="CREDIT">CREDIT</option>
                                <option value="DEBIT">DEBIT</option>
                            </select>
                        </label>
                        <div className="flex-1" />
                        <button className="btn-secondary" onClick={exportStatement}>
                            {t('accounts.exportStatement')}
                        </button>
                    </div>

                    <div className="card mt-4 overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead className="bg-slate-50 text-slate-700">
                                <tr>
                                    <th scope="col" className="text-left px-3 py-2">
                                        When
                                    </th>
                                    <th scope="col" className="text-left px-3 py-2">
                                        {t('accounts.counterparty')}
                                    </th>
                                    <th scope="col" className="text-left px-3 py-2">
                                        {t('accounts.reference')}
                                    </th>
                                    <th scope="col" className="text-right px-3 py-2">
                                        {t('accounts.amount')}
                                    </th>
                                    <th scope="col" className="text-left px-3 py-2">
                                        {t('accounts.status')}
                                    </th>
                                </tr>
                            </thead>
                            <tbody>
                                {items === null && (
                                    <tr>
                                        <td colSpan={5} className="px-3 py-6 text-center text-slate-500">
                                            {t('common.loading')}
                                        </td>
                                    </tr>
                                )}
                                {items && items.length === 0 && (
                                    <tr>
                                        <td colSpan={5} className="px-3 py-6 text-center text-slate-500">
                                            {t('accounts.noTransactions')}
                                        </td>
                                    </tr>
                                )}
                                {items?.map((tx) => (
                                    <tr key={tx.transactionId} className="border-t border-slate-100">
                                        <td className="px-3 py-2 text-xs tabular-nums">
                                            {formatDateTime(tx.postedAt, i18n.language)}
                                        </td>
                                        <td className="px-3 py-2">
                                            <p>{tx.counterpartyName ?? '—'}</p>
                                            {tx.counterpartyIban && (
                                                <p className="text-xs text-slate-500 font-mono">
                                                    {tx.counterpartyIban}
                                                </p>
                                            )}
                                        </td>
                                        <td className="px-3 py-2 text-slate-600">
                                            {tx.reference ?? '—'}
                                        </td>
                                        <td
                                            className={`px-3 py-2 text-right tabular-nums font-medium ${
                                                tx.direction === 'CREDIT'
                                                    ? 'text-jrpay-green'
                                                    : 'text-slate-900'
                                            }`}
                                        >
                                            {tx.direction === 'CREDIT' ? '+' : '−'}
                                            {formatAmount(tx.amount, tx.currency, i18n.language)}
                                        </td>
                                        <td className="px-3 py-2 text-xs">
                                            <span className="badge badge-neutral">
                                                {t(`status.${tx.status}`, { defaultValue: tx.status })}
                                            </span>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {items && items.length > 0 && (
                        <p className="text-xs text-slate-500 mt-2 text-right">
                            +{formatAmount(totalCredits, detail.currency, i18n.language)} ·{' '}
                            −{formatAmount(totalDebits, detail.currency, i18n.language)}
                        </p>
                    )}

                    {pageMeta && pageMeta.totalPages > 1 && (
                        <nav
                            aria-label="pagination"
                            className="flex items-center justify-end gap-2 mt-3 text-sm"
                        >
                            <button
                                className="btn-secondary py-1"
                                onClick={() => setParam('page', String(Math.max(0, page - 1)))}
                                disabled={page === 0}
                            >
                                {t('common.prev')}
                            </button>
                            <span aria-live="polite">
                                {t('common.page')} {page + 1} {t('common.of')}{' '}
                                {pageMeta.totalPages}
                            </span>
                            <button
                                className="btn-secondary py-1"
                                onClick={() =>
                                    setParam('page', String(Math.min(pageMeta.totalPages - 1, page + 1)))
                                }
                                disabled={page >= pageMeta.totalPages - 1}
                            >
                                {t('common.next')}
                            </button>
                        </nav>
                    )}
                </>
            )}
        </section>
    );
}

function parsePage(raw: string | null): number {
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0;
}
