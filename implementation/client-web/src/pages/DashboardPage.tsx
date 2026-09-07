// UC-G02 — customer dashboard. Lists the customer's accounts with balances
// and a "recent activity" footer showing the latest 10 cross-account
// transactions.

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useToasts } from '../components/Toast';
import { formatAmount, formatDateTime, maskIban } from '../lib/format';

interface AccountSummary {
    accountId: string;
    iban?: string;
    ibanMasked?: string;
    nickname?: string;
    type: string;
    currency: string;
    bookedBalance?: string;
    availableBalance?: string;
    status: string;
}

interface TransactionSummary {
    transactionId: string;
    accountId?: string;
    postedAt: string;
    amount: string;
    currency: string;
    direction: 'DEBIT' | 'CREDIT';
    status: string;
    counterpartyName?: string;
    reference?: string;
}

export function DashboardPage() {
    const { t, i18n } = useTranslation();
    const { showProblem } = useToasts();
    const { customerId } = useAuth();
    const [accounts, setAccounts] = useState<AccountSummary[] | null>(null);
    const [recent, setRecent] = useState<TransactionSummary[] | null>(null);

    useEffect(() => {
        api<{ items: AccountSummary[] }>('/api/v1/customer/accounts')
            .then((r) => setAccounts(r.items ?? []))
            .catch((e) => showProblem(e));
    }, [showProblem]);

    useEffect(() => {
        api<{ items: TransactionSummary[] }>('/api/v1/customer/accounts/transactions/recent', {
            query: { limit: 10 },
        })
            .then((r) => setRecent(r.items ?? []))
            .catch(() => setRecent([]));
    }, []);

    const total = (accounts ?? []).reduce((sum, a) => {
        const n = Number(a.availableBalance ?? a.bookedBalance ?? 0);
        return Number.isFinite(n) ? sum + n : sum;
    }, 0);
    const primaryCurrency = accounts?.[0]?.currency ?? 'NOK';

    return (
        <section aria-labelledby="dash-title">
            <header className="flex items-baseline justify-between flex-wrap gap-3">
                <div>
                    <h1 id="dash-title" className="text-2xl font-semibold text-jrpay-navy">
                        {t('dashboard.title')}
                    </h1>
                    {customerId && (
                        <p className="text-xs text-slate-500 mt-1 font-mono">{customerId}</p>
                    )}
                </div>
                {accounts && accounts.length > 0 && (
                    <div className="card px-4 py-2 text-right">
                        <p className="text-xs uppercase tracking-wide text-slate-500">
                            {t('dashboard.totalBalance')}
                        </p>
                        <p className="text-2xl font-semibold tabular-nums text-jrpay-navy">
                            {formatAmount(total, primaryCurrency, i18n.language)}
                        </p>
                    </div>
                )}
            </header>

            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700 mt-6 mb-2">
                {t('accounts.title')}
            </h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {accounts === null && (
                    <p className="text-slate-500">{t('common.loading')}</p>
                )}
                {accounts && accounts.length === 0 && (
                    <p className="text-slate-500">{t('common.noResults')}</p>
                )}
                {accounts?.map((a) => (
                    <Link
                        key={a.accountId}
                        to={`/accounts/${encodeURIComponent(a.accountId)}`}
                        className="card p-4 hover:shadow-md transition-shadow border border-transparent hover:border-jrpay-navy"
                    >
                        <p className="text-xs uppercase tracking-wide text-slate-500">
                            {a.nickname ?? a.type}
                        </p>
                        <p className="font-mono text-xs text-slate-600 mt-1">
                            {a.ibanMasked ?? (a.iban ? maskIban(a.iban) : '')}
                        </p>
                        <p className="text-2xl font-semibold mt-3 tabular-nums text-jrpay-navy">
                            {formatAmount(a.availableBalance ?? '0', a.currency, i18n.language)}
                        </p>
                        <p className="text-xs text-slate-500 mt-1">
                            {t('accounts.available')}
                        </p>
                    </Link>
                ))}
            </div>

            <section className="card mt-6 p-4">
                <header className="flex items-baseline justify-between mb-2">
                    <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700">
                        {t('dashboard.recentActivity')}
                    </h2>
                </header>
                {recent === null && <p className="text-slate-500">{t('common.loading')}</p>}
                {recent && recent.length === 0 && (
                    <p className="text-slate-500">{t('accounts.noTransactions')}</p>
                )}
                {recent && recent.length > 0 && (
                    <ul className="divide-y divide-slate-100">
                        {recent.map((tx) => (
                            <li
                                key={tx.transactionId}
                                className="py-2 flex items-center justify-between text-sm"
                            >
                                <div>
                                    <p className="font-medium">
                                        {tx.counterpartyName ?? '—'}
                                    </p>
                                    <p className="text-xs text-slate-500">
                                        {formatDateTime(tx.postedAt, i18n.language)}{' '}
                                        {tx.reference && `· ${tx.reference}`}
                                    </p>
                                </div>
                                <p
                                    className={`tabular-nums font-medium ${
                                        tx.direction === 'CREDIT'
                                            ? 'text-jrpay-green'
                                            : 'text-slate-900'
                                    }`}
                                >
                                    {tx.direction === 'CREDIT' ? '+' : '−'}
                                    {formatAmount(tx.amount, tx.currency, i18n.language)}
                                </p>
                            </li>
                        ))}
                    </ul>
                )}
            </section>
        </section>
    );
}
