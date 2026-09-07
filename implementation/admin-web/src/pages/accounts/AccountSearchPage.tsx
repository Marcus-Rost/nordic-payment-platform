// UC-G35 §6.1 — admin account search.
//
// Three mutually-exclusive modes:
//   - name (fuzzy)
//   - iban (exact)
//   - fodselsnummer (exact, requires LoA-3 step-up issued in last 5 min;
//     server returns 401 step-up-required when missing)
//
// On step-up-required, we run the UC-G51 SCA modal with action=SEARCH_ACCOUNT
// (resourceType=account, resourceId=fodselsnummer:<sha256(q)> per the
// dynamic-linking spec for searches), then replay with the Step-Up-Token.
//
// Search-results pagination is server-driven; the page also opens the
// "create account" modal (§6.6).

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

type Mode = 'name' | 'iban' | 'fodselsnummer';

interface AccountSummary {
    accountId: string;
    ibanMasked: string;
    type: string;
    currency: string;
    status: 'OPEN' | 'FROZEN' | 'CLOSED';
    holderNameMasked: string;
    customerId?: string;
    bookedBalance?: string;
    availableBalance?: string;
    freezeReason?: string;
}

interface SearchResults {
    items: AccountSummary[];
    page: { number: number; size: number; totalElements: number; totalPages: number };
    truncated: boolean;
}

export function AccountSearchPage() {
    const { t } = useTranslation();
    const { showProblem } = useToasts();

    const [mode, setMode] = useState<Mode>('name');
    const [query, setQuery] = useState('');
    const [results, setResults] = useState<SearchResults | null>(null);
    const [loading, setLoading] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [createOpen, setCreateOpen] = useState(false);

    async function runSearch(stepUpToken?: string) {
        if (query.trim().length < 2) return;
        setLoading(true);
        try {
            const res = await api<SearchResults>('/api/v1/admin/accounts/search', {
                query: { mode, q: query.trim(), page: 0, size: 25 },
                stepUpToken,
            });
            setResults(res);
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
                const h = await sha256HexNfc(`${mode}:${query.trim()}`);
                setPendingHash(h);
                setScaOpen(true);
                setLoading(false);
                return;
            }
            showProblem(e);
        } finally {
            setLoading(false);
        }
    }

    function onSubmit(e: React.FormEvent) {
        e.preventDefault();
        void runSearch();
    }

    return (
        <section aria-labelledby="search-title">
            <header className="flex items-baseline justify-between gap-3 flex-wrap">
                <h1 id="search-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('accounts.searchTitle')}
                </h1>
                <button className="btn-primary" onClick={() => setCreateOpen(true)}>
                    {t('accounts.createAccount')}
                </button>
            </header>

            <form className="bg-white rounded-lg shadow-sm mt-4 p-4" onSubmit={onSubmit}>
                <fieldset className="flex flex-wrap gap-2">
                    <legend className="sr-only">Search mode</legend>
                    {(['name', 'iban', 'fodselsnummer'] as const).map((m) => (
                        <label
                            key={m}
                            className={`px-3 py-1 rounded-full text-xs cursor-pointer border ${
                                mode === m
                                    ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                    : 'bg-white text-slate-700 border-slate-300 hover:border-jrpay-navy'
                            }`}
                        >
                            <input
                                type="radio"
                                name="mode"
                                value={m}
                                className="sr-only"
                                checked={mode === m}
                                onChange={() => setMode(m)}
                            />
                            {m === 'name'
                                ? t('accounts.modeName')
                                : m === 'iban'
                                  ? t('accounts.modeIban')
                                  : t('accounts.modeFnr')}
                        </label>
                    ))}
                </fieldset>

                <div className="flex gap-2 mt-3">
                    <label htmlFor="acct-q" className="sr-only">
                        {t('common.search')}
                    </label>
                    <input
                        id="acct-q"
                        type="search"
                        className="input flex-1"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        minLength={2}
                        maxLength={140}
                        autoComplete={mode === 'fodselsnummer' ? 'off' : 'on'}
                        spellCheck={false}
                        placeholder={
                            mode === 'iban'
                                ? 'NO9300000000000'
                                : mode === 'fodselsnummer'
                                  ? '11 digits'
                                  : 'Holder name'
                        }
                    />
                    <button
                        type="submit"
                        className="btn-primary"
                        disabled={loading || query.trim().length < 2}
                    >
                        {t('common.search')}
                    </button>
                </div>
                {mode === 'fodselsnummer' && (
                    <p className="text-xs text-slate-500 mt-2">
                        {t('login.scaNotice')}
                    </p>
                )}
            </form>

            {results?.truncated && (
                <div
                    role="alert"
                    className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"
                >
                    Result set was capped — refine your search.
                </div>
            )}

            <div className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                Account
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                IBAN
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Holder
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Status
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Type
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Available
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {!loading && results && results.items.length === 0 && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('accounts.noResults')}
                                </td>
                            </tr>
                        )}
                        {!loading &&
                            results?.items.map((it) => (
                                <tr key={it.accountId} className="border-t border-slate-100 hover:bg-slate-50">
                                    <td className="px-3 py-2">
                                        <Link
                                            to={`/accounts/${encodeURIComponent(it.accountId)}`}
                                            className="text-jrpay-navy hover:underline font-mono text-xs"
                                        >
                                            {it.accountId.slice(0, 12)}…
                                        </Link>
                                    </td>
                                    <td className="px-3 py-2 font-mono text-xs">{it.ibanMasked}</td>
                                    <td className="px-3 py-2">{it.holderNameMasked}</td>
                                    <td className="px-3 py-2">
                                        <span className={statusClass(it.status)}>{it.status}</span>
                                        {it.freezeReason && (
                                            <p className="text-xs text-slate-500 mt-0.5">
                                                {it.freezeReason}
                                            </p>
                                        )}
                                    </td>
                                    <td className="px-3 py-2">{it.type}</td>
                                    <td className="px-3 py-2 tabular-nums">
                                        {it.availableBalance ?? '—'} {it.currency}
                                    </td>
                                </tr>
                            ))}
                    </tbody>
                </table>
            </div>

            {scaOpen && pendingHash && (
                <ScaModal
                    inputs={{
                        class: 'admin-action',
                        action: 'SEARCH_ACCOUNT',
                        resourceType: 'account',
                        resourceId: `${mode}:search`,
                        notesHash: pendingHash,
                    }}
                    onResolve={(token) => {
                        setScaOpen(false);
                        void runSearch(token);
                    }}
                    onCancel={() => {
                        setScaOpen(false);
                        setPendingHash(null);
                    }}
                />
            )}

            {createOpen && (
                <CreateAccountModal
                    onClose={() => setCreateOpen(false)}
                    onCreated={(accountId) => {
                        setCreateOpen(false);
                        // Navigate via hard link rather than useNavigate to keep this
                        // page stateless re: react-router internals.
                        window.location.assign(`/accounts/${encodeURIComponent(accountId)}`);
                    }}
                />
            )}
        </section>
    );
}

function CreateAccountModal({
    onClose,
    onCreated,
}: {
    onClose: () => void;
    onCreated: (accountId: string) => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [form, setForm] = useState({
        customerId: '',
        type: 'CHECKING',
        currency: 'NOK',
        nickname: '',
        notes: '',
        confirmedCriteria: false,
    });
    const [scaOpen, setScaOpen] = useState(false);
    const [pendingHash, setPendingHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const valid =
        form.customerId.trim() !== '' &&
        form.notes.length >= 50 &&
        form.confirmedCriteria;

    async function startSca() {
        if (!valid) return;
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(form.notes);
            setPendingHash(h);
            setScaOpen(true);
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    async function postCreate(stepUpToken: string) {
        try {
            const res = await api<{ accountId: string }>('/api/v1/admin/accounts', {
                method: 'POST',
                stepUpToken,
                body: {
                    customerId: form.customerId.trim(),
                    type: form.type,
                    currency: form.currency,
                    nickname: form.nickname || undefined,
                    notes: form.notes,
                    confirmedCriteria: true,
                },
            });
            showInfo(t('accounts.createAccount'));
            onCreated(res.accountId);
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
                    action: 'CREATE_ACCOUNT',
                    resourceType: 'account',
                    resourceId: `customer:${form.customerId}`,
                    notesHash: pendingHash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void postCreate(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <Modal titleId="create-acct-title" onClose={onClose}>
            <h2 id="create-acct-title" className="text-lg font-semibold text-jrpay-navy">
                {t('accounts.createAccount')}
            </h2>
            <div className="grid grid-cols-2 gap-3 mt-4 text-sm">
                <label>
                    Customer id
                    <input
                        className="input mt-1"
                        value={form.customerId}
                        onChange={(e) => setForm((f) => ({ ...f, customerId: e.target.value }))}
                    />
                </label>
                <label>
                    Type
                    <select
                        className="input mt-1"
                        value={form.type}
                        onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}
                    >
                        <option>CHECKING</option>
                        <option>SAVINGS</option>
                        <option>OPERATING</option>
                    </select>
                </label>
                <label>
                    Currency
                    <select
                        className="input mt-1"
                        value={form.currency}
                        onChange={(e) => setForm((f) => ({ ...f, currency: e.target.value }))}
                    >
                        <option>NOK</option>
                        <option>EUR</option>
                        <option>USD</option>
                        <option>SEK</option>
                        <option>DKK</option>
                    </select>
                </label>
                <label>
                    Nickname (optional)
                    <input
                        className="input mt-1"
                        value={form.nickname}
                        onChange={(e) => setForm((f) => ({ ...f, nickname: e.target.value }))}
                    />
                </label>
            </div>
            <label className="block mt-3 text-sm">
                {t('accounts.freezeNotes')}
                <textarea
                    className="input mt-1"
                    rows={4}
                    value={form.notes}
                    onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
                />
            </label>
            <p
                className={`text-xs text-right ${form.notes.length >= 50 ? 'text-jrpay-green' : 'text-jrpay-red'}`}
            >
                {form.notes.length} / 50
            </p>

            <label className="flex items-start gap-2 mt-2 text-sm cursor-pointer">
                <input
                    type="checkbox"
                    checked={form.confirmedCriteria}
                    onChange={(e) =>
                        setForm((f) => ({ ...f, confirmedCriteria: e.target.checked }))
                    }
                />
                <span>{t('accounts.confirmedCriteria')}</span>
            </label>

            <div className="mt-6 flex justify-end gap-2">
                <button className="btn-secondary" onClick={onClose} disabled={submitting}>
                    {t('common.cancel')}
                </button>
                <button
                    className="btn-primary"
                    onClick={startSca}
                    disabled={!valid || submitting}
                >
                    {t('fraud.decideSubmit')}
                </button>
            </div>
        </Modal>
    );
}

function statusClass(s: string): string {
    if (s === 'FROZEN') return 'badge badge-medium';
    if (s === 'CLOSED') return 'badge badge-neutral';
    return 'badge badge-low';
}
