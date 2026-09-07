// UC-G31 — Fraud queue list view.
//
// Implements:
//   - filters: status[], score band, free-text search across rule / debtor /
//     creditor / case-id (server filters the canonical fields, the rest are
//     applied client-side to the page in view).
//   - counters: openTotal / assignedToMe / slaBreached, refreshed on every
//     queue load and on relevant SSE events.
//   - paginated, sortable table with score-band coloured score badge.
//   - live updates via SSE: mint single-use token, open EventSource, on every
//     `case.created | case.assigned | case.decided | sla.breached` event we
//     bump a refresh counter that re-fetches the current page.
//   - click-through to `/fraud/:caseId`.
//
// Filters are encoded into the URL search params so reload keeps the view
// stable, deep-links work, and back/forward navigation behaves naturally.

import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useAuth } from '../../auth/AuthContext';
import { useToasts } from '../../components/Toast';

type Status = 'OPEN' | 'ASSIGNED' | 'CONFIRMED_FRAUD' | 'DISMISSED';
type ScoreBand = 'LOW' | 'MEDIUM' | 'HIGH';

interface FraudCaseSummary {
    caseId: string;
    status: Status;
    score: number | string;
    scoreBand?: ScoreBand;
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

interface PageMeta {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

interface Counters {
    openTotal: number;
    assignedToMe: number;
    slaBreached: number;
}

interface FraudCasePageResponse {
    items: FraudCaseSummary[];
    page: PageMeta;
    counters: Counters;
}

interface StreamTokenResponse {
    streamToken: string;
    expiresIn: number;
}

const DEFAULT_STATUSES: Status[] = ['OPEN', 'ASSIGNED'];
const ALL_STATUSES: Status[] = ['OPEN', 'ASSIGNED', 'CONFIRMED_FRAUD', 'DISMISSED'];
const PAGE_SIZE = 25;

export function FraudQueuePage() {
    const { t, i18n } = useTranslation();
    const { showProblem } = useToasts();
    const { operatorId } = useAuth();
    const [searchParams, setSearchParams] = useSearchParams();

    const statuses = parseStatuses(searchParams.getAll('status'));
    const minScore = parseScore(searchParams.get('minScore'));
    const maxScore = parseScore(searchParams.get('maxScore'));
    const localQuery = searchParams.get('q') ?? '';
    const page = parsePage(searchParams.get('page'));

    const [data, setData] = useState<FraudCasePageResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshTick, setRefreshTick] = useState(0);
    const [livePulse, setLivePulse] = useState(false);

    const abortRef = useRef<AbortController | null>(null);

    // Fetch the queue when filters / page / refresh tick change.
    useEffect(() => {
        abortRef.current?.abort();
        const ctrl = new AbortController();
        abortRef.current = ctrl;

        setLoading(true);
        const query: Record<string, string | number | boolean | string[] | undefined> = {
            status: statuses.length ? statuses : undefined,
            minScore: minScore ?? undefined,
            maxScore: maxScore ?? undefined,
            page,
            size: PAGE_SIZE,
        };

        api<FraudCasePageResponse>('/api/v1/admin/fraud/cases', { query, signal: ctrl.signal })
            .then((res) => {
                setData(res);
                setLoading(false);
            })
            .catch((e) => {
                if (e instanceof DOMException && e.name === 'AbortError') return;
                showProblem(e);
                setLoading(false);
            });

        return () => ctrl.abort();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [statuses.join(','), minScore, maxScore, page, refreshTick]);

    // SSE live updates: mint a token, open EventSource, on each relevant event
    // bump the refresh counter so the queue re-fetches.
    useEffect(() => {
        let closed = false;
        let source: EventSource | null = null;
        let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

        async function connect() {
            if (closed) return;
            try {
                const tok = await api<StreamTokenResponse>(
                    '/api/v1/admin/fraud/cases/stream-token',
                    { method: 'POST', body: {} }
                );
                if (closed) return;
                const url = `/api/v1/admin/fraud/cases/stream?token=${encodeURIComponent(tok.streamToken)}`;
                source = new EventSource(url);

                const onAny = () => {
                    setLivePulse(true);
                    setTimeout(() => setLivePulse(false), 1_500);
                    setRefreshTick((n) => n + 1);
                };
                source.addEventListener('case.created', onAny);
                source.addEventListener('case.assigned', onAny);
                source.addEventListener('case.decided', onAny);
                source.addEventListener('sla.breached', onAny);
                source.onerror = () => {
                    source?.close();
                    source = null;
                    if (!closed) {
                        // exponential-ish backoff via fixed 5 s reconnect.
                        reconnectTimer = setTimeout(connect, 5_000);
                    }
                };
            } catch (e) {
                // SSE token mint failed — silent (counters and refresh still work
                // when the operator manually reloads); surface only network-level
                // problems, not auth-level (those are handled at AuthGuard).
                if (e instanceof ProblemError && e.problem.status === 401) return;
                if (!closed) reconnectTimer = setTimeout(connect, 10_000);
            }
        }

        connect();
        return () => {
            closed = true;
            if (reconnectTimer) clearTimeout(reconnectTimer);
            source?.close();
        };
    }, []);

    function updateParams(patch: Record<string, string | string[] | null>) {
        const next = new URLSearchParams(searchParams);
        for (const [k, v] of Object.entries(patch)) {
            next.delete(k);
            if (v === null || v === undefined) continue;
            if (Array.isArray(v)) {
                for (const each of v) next.append(k, each);
            } else if (v !== '') {
                next.set(k, v);
            }
        }
        setSearchParams(next, { replace: false });
    }

    function toggleStatus(s: Status) {
        const next = statuses.includes(s) ? statuses.filter((x) => x !== s) : [...statuses, s];
        updateParams({ status: next.length ? next : null, page: '0' });
    }

    function setRange(field: 'minScore' | 'maxScore', raw: string) {
        if (raw === '') {
            updateParams({ [field]: null, page: '0' });
            return;
        }
        const n = Number(raw);
        if (!Number.isFinite(n) || n < 0 || n > 1) return;
        updateParams({ [field]: String(n), page: '0' });
    }

    const visibleItems = useMemo(() => {
        if (!data) return [];
        if (!localQuery.trim()) return data.items;
        const needle = localQuery.trim().toLowerCase();
        return data.items.filter((it) => {
            const haystack = [
                it.caseId,
                it.debtorAccountId,
                it.creditorIbanMasked,
                it.holderNameMasked,
                ...(it.triggeredRules ?? []),
            ]
                .filter(Boolean)
                .join(' ')
                .toLowerCase();
            return haystack.includes(needle);
        });
    }, [data, localQuery]);

    const counters = data?.counters ?? { openTotal: 0, assignedToMe: 0, slaBreached: 0 };

    return (
        <section aria-labelledby="fraud-queue-title">
            <header className="flex items-baseline justify-between gap-4 flex-wrap">
                <h1 id="fraud-queue-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('fraud.queueTitle')}
                </h1>
                <div className="flex items-center gap-2 text-sm text-slate-600" aria-live="polite">
                    {livePulse && (
                        <span
                            className="badge badge-info"
                            role="status"
                            aria-label="live update received"
                        >
                            ● Live
                        </span>
                    )}
                    <button
                        className="btn-secondary text-sm py-1"
                        onClick={() => setRefreshTick((n) => n + 1)}
                    >
                        {t('common.retry')}
                    </button>
                </div>
            </header>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-4">
                <CounterCard label={t('fraud.openTotal')} value={counters.openTotal} tone="info" />
                <CounterCard
                    label={t('fraud.assignedToMe')}
                    value={counters.assignedToMe}
                    tone="neutral"
                />
                <CounterCard
                    label={t('fraud.slaBreached')}
                    value={counters.slaBreached}
                    tone={counters.slaBreached > 0 ? 'danger' : 'neutral'}
                />
            </div>

            <div className="bg-white rounded-lg shadow-sm mt-4 p-4">
                <div className="flex flex-wrap gap-3 items-end">
                    <fieldset className="flex flex-wrap gap-2">
                        <legend className="text-sm font-medium text-slate-700 mb-1">
                            Status
                        </legend>
                        {ALL_STATUSES.map((s) => (
                            <label
                                key={s}
                                className={`px-3 py-1 rounded-full text-xs cursor-pointer border ${
                                    statuses.includes(s)
                                        ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                        : 'bg-white text-slate-700 border-slate-300 hover:border-jrpay-navy'
                                }`}
                            >
                                <input
                                    type="checkbox"
                                    className="sr-only"
                                    checked={statuses.includes(s)}
                                    onChange={() => toggleStatus(s)}
                                />
                                {s}
                            </label>
                        ))}
                    </fieldset>

                    <div className="flex items-end gap-2">
                        <label className="text-sm">
                            <span className="block text-slate-700 mb-1">Min score</span>
                            <input
                                type="number"
                                min="0"
                                max="1"
                                step="0.01"
                                className="input w-24"
                                value={minScore == null ? '' : String(minScore)}
                                onChange={(e) => setRange('minScore', e.target.value)}
                            />
                        </label>
                        <label className="text-sm">
                            <span className="block text-slate-700 mb-1">Max score</span>
                            <input
                                type="number"
                                min="0"
                                max="1"
                                step="0.01"
                                className="input w-24"
                                value={maxScore == null ? '' : String(maxScore)}
                                onChange={(e) => setRange('maxScore', e.target.value)}
                            />
                        </label>
                    </div>

                    <label className="text-sm flex-1 min-w-[12rem]">
                        <span className="block text-slate-700 mb-1">{t('common.search')}</span>
                        <input
                            type="search"
                            className="input"
                            placeholder="case-id / IBAN / rule"
                            value={localQuery}
                            onChange={(e) =>
                                updateParams({
                                    q: e.target.value,
                                    page: '0',
                                })
                            }
                        />
                    </label>
                </div>
            </div>

            <div className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.score')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Status
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.case')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.amount')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.creditor')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.rules')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.assignee')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('fraud.sla')}
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading && (
                            <tr>
                                <td colSpan={8} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {!loading && visibleItems.length === 0 && (
                            <tr>
                                <td colSpan={8} className="px-3 py-6 text-center text-slate-500">
                                    {t('accounts.noResults')}
                                </td>
                            </tr>
                        )}
                        {!loading &&
                            visibleItems.map((it) => (
                                <FraudRow
                                    key={it.caseId}
                                    item={it}
                                    operatorId={operatorId}
                                    locale={i18n.language}
                                />
                            ))}
                    </tbody>
                </table>
            </div>

            {data && data.page.totalPages > 1 && (
                <Pagination page={data.page} onChange={(p) => updateParams({ page: String(p) })} />
            )}
        </section>
    );
}

function FraudRow({
    item,
    operatorId,
    locale,
}: {
    item: FraudCaseSummary;
    operatorId: string | null;
    locale: string;
}) {
    const score = typeof item.score === 'string' ? Number(item.score) : item.score;
    const band = item.scoreBand ?? bandFor(score);
    const slaBreached = item.slaBreached === true || isPast(item.slaDeadline);
    const assignedToMe = !!operatorId && item.assignee === operatorId;

    return (
        <tr className="border-t border-slate-100 hover:bg-slate-50">
            <td className="px-3 py-2 align-top">
                <span className={scoreClass(band)}>
                    {Number.isFinite(score) ? score.toFixed(2) : '—'}
                </span>
            </td>
            <td className="px-3 py-2 align-top">
                <span className={statusBadgeClass(item.status)}>{item.status}</span>
            </td>
            <td className="px-3 py-2 align-top">
                <Link
                    to={`/fraud/${encodeURIComponent(item.caseId)}`}
                    className="text-jrpay-navy underline-offset-2 hover:underline font-mono text-xs"
                >
                    {item.caseId.slice(0, 8)}…
                </Link>
                <p className="text-xs text-slate-500 mt-0.5">{item.holderNameMasked}</p>
            </td>
            <td className="px-3 py-2 align-top tabular-nums">
                {formatAmount(item.amount, item.currency, locale)}
            </td>
            <td className="px-3 py-2 align-top text-xs font-mono">{item.creditorIbanMasked}</td>
            <td className="px-3 py-2 align-top">
                <div className="flex flex-wrap gap-1">
                    {(item.triggeredRules ?? []).map((r) => (
                        <span key={r} className="badge badge-neutral">
                            {r}
                        </span>
                    ))}
                </div>
            </td>
            <td className="px-3 py-2 align-top text-xs">
                {item.assignee ? (
                    <span className={assignedToMe ? 'font-semibold text-jrpay-navy' : ''}>
                        {assignedToMe ? '★ ' : ''}
                        {item.assignee}
                    </span>
                ) : (
                    <span className="text-slate-400">—</span>
                )}
            </td>
            <td className="px-3 py-2 align-top text-xs">
                <span className={slaBreached ? 'text-jrpay-red font-semibold' : 'text-slate-600'}>
                    {formatDateTime(item.slaDeadline, locale)}
                </span>
            </td>
        </tr>
    );
}

function CounterCard({
    label,
    value,
    tone,
}: {
    label: string;
    value: number;
    tone: 'info' | 'neutral' | 'danger';
}) {
    const toneClass =
        tone === 'danger'
            ? 'text-jrpay-red'
            : tone === 'info'
              ? 'text-jrpay-navy'
              : 'text-slate-700';
    return (
        <div className="bg-white rounded-lg shadow-sm p-4">
            <p className="text-xs uppercase tracking-wide text-slate-500">{label}</p>
            <p className={`text-2xl font-semibold mt-1 tabular-nums ${toneClass}`}>{value}</p>
        </div>
    );
}

function Pagination({ page, onChange }: { page: PageMeta; onChange: (p: number) => void }) {
    const { t } = useTranslation();
    return (
        <nav
            aria-label="pagination"
            className="flex items-center justify-end gap-2 mt-4 text-sm"
        >
            <button
                className="btn-secondary py-1"
                onClick={() => onChange(Math.max(0, page.number - 1))}
                disabled={page.number === 0}
            >
                {t('common.prev')}
            </button>
            <span aria-live="polite">
                {t('common.page')} {page.number + 1} {t('common.of')} {page.totalPages}
            </span>
            <button
                className="btn-secondary py-1"
                onClick={() => onChange(Math.min(page.totalPages - 1, page.number + 1))}
                disabled={page.number >= page.totalPages - 1}
            >
                {t('common.next')}
            </button>
        </nav>
    );
}

function bandFor(score: number): ScoreBand {
    if (!Number.isFinite(score)) return 'LOW';
    if (score >= 0.85) return 'HIGH';
    if (score >= 0.6) return 'MEDIUM';
    return 'LOW';
}

function scoreClass(band: ScoreBand): string {
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

function parseStatuses(raw: string[]): Status[] {
    if (raw.length === 0) return DEFAULT_STATUSES;
    return raw.filter((s): s is Status => (ALL_STATUSES as string[]).includes(s));
}

function parseScore(raw: string | null): number | null {
    if (raw === null) return null;
    const n = Number(raw);
    if (!Number.isFinite(n) || n < 0 || n > 1) return null;
    return n;
}

function parsePage(raw: string | null): number {
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0;
}
