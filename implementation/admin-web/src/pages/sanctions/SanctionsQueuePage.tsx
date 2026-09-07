// UC-G34 §6.1 — sanctions hit queue.
//
// Same pattern as the fraud queue: filters in URL params, server-side
// pagination, SSE live updates, click-through to detail. Counters here
// are openTotal + slaBreached (per spec).

import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';

type Status =
    | 'OPEN'
    | 'CONFIRMED_SANCTION'
    | 'FALSE_POSITIVE'
    | 'RELEASE_FOR_FRAUD_REVIEW';

interface HitSummary {
    hitId: string;
    status: Status;
    paymentId?: string;
    debtorIbanMasked?: string;
    beneficiaryNameOnTransaction?: string;
    matchedEntityName: string;
    matchedList: string;
    score: number | string;
    assignee?: string | null;
    createdAt: string;
    slaDeadline: string;
    slaBreached?: boolean;
}

interface PageMeta {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

interface Counters {
    openTotal: number;
    slaBreached: number;
}

interface HitsPage {
    items: HitSummary[];
    page: PageMeta;
    counters: Counters;
}

interface ListMetaItem {
    listId: string;
    displayName: string;
    version: string;
    lastUpdatedAt: string;
    entriesCount: number;
    staleHours?: number;
    sourceUrl?: string;
}

const ALL_STATUSES: Status[] = [
    'OPEN',
    'CONFIRMED_SANCTION',
    'FALSE_POSITIVE',
    'RELEASE_FOR_FRAUD_REVIEW',
];
const PAGE_SIZE = 25;

export function SanctionsQueuePage() {
    const { t, i18n } = useTranslation();
    const { showProblem } = useToasts();
    const [searchParams, setSearchParams] = useSearchParams();

    const statuses = parseStatuses(searchParams.getAll('status'));
    const list = searchParams.get('list') ?? '';
    const matchedEntity = searchParams.get('matchedEntity') ?? '';
    const page = parsePage(searchParams.get('page'));

    const [data, setData] = useState<HitsPage | null>(null);
    const [lists, setLists] = useState<ListMetaItem[] | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshTick, setRefreshTick] = useState(0);
    const [livePulse, setLivePulse] = useState(false);
    const abortRef = useRef<AbortController | null>(null);

    useEffect(() => {
        abortRef.current?.abort();
        const ctrl = new AbortController();
        abortRef.current = ctrl;

        setLoading(true);
        const query: Record<string, string | number | boolean | string[] | undefined> = {
            status: statuses.length ? statuses : undefined,
            list: list || undefined,
            matchedEntity: matchedEntity || undefined,
            page,
            size: PAGE_SIZE,
        };
        api<HitsPage>('/api/v1/admin/sanctions/hits', { query, signal: ctrl.signal })
            .then(setData)
            .catch((e) => {
                if (e instanceof DOMException && e.name === 'AbortError') return;
                showProblem(e);
            })
            .finally(() => setLoading(false));
        return () => ctrl.abort();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [statuses.join(','), list, matchedEntity, page, refreshTick]);

    useEffect(() => {
        api<{ items: ListMetaItem[] }>('/api/v1/admin/sanctions/lists')
            .then((r) => setLists(r.items))
            .catch(() => setLists([]));
    }, []);

    useEffect(() => {
        let closed = false;
        let source: EventSource | null = null;
        let timer: ReturnType<typeof setTimeout> | null = null;

        async function connect() {
            if (closed) return;
            try {
                const tok = await api<{ streamToken: string }>(
                    '/api/v1/admin/sanctions/hits/stream-token',
                    { method: 'POST', body: {} }
                );
                if (closed) return;
                source = new EventSource(
                    `/api/v1/admin/sanctions/hits/stream?token=${encodeURIComponent(tok.streamToken)}`
                );
                const onAny = () => {
                    setLivePulse(true);
                    setTimeout(() => setLivePulse(false), 1_500);
                    setRefreshTick((n) => n + 1);
                };
                source.addEventListener('hit.created', onAny);
                source.addEventListener('hit.decided', onAny);
                source.addEventListener('sla.breached', onAny);
                source.onerror = () => {
                    source?.close();
                    source = null;
                    if (!closed) timer = setTimeout(connect, 5_000);
                };
            } catch (e) {
                if (e instanceof ProblemError && e.problem.status === 401) return;
                if (!closed) timer = setTimeout(connect, 10_000);
            }
        }
        connect();
        return () => {
            closed = true;
            if (timer) clearTimeout(timer);
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
        setSearchParams(next);
    }

    function toggleStatus(s: Status) {
        const next = statuses.includes(s) ? statuses.filter((x) => x !== s) : [...statuses, s];
        updateParams({ status: next.length ? next : null, page: '0' });
    }

    const stalest = useMemo(() => {
        if (!lists || lists.length === 0) return null;
        return lists.reduce<ListMetaItem | null>(
            (max, l) =>
                !max || (l.staleHours ?? 0) > (max.staleHours ?? 0) ? l : max,
            null
        );
    }, [lists]);

    const counters = data?.counters ?? { openTotal: 0, slaBreached: 0 };

    return (
        <section aria-labelledby="sanctions-queue-title">
            <header className="flex items-baseline justify-between gap-4 flex-wrap">
                <h1
                    id="sanctions-queue-title"
                    className="text-2xl font-semibold text-jrpay-navy"
                >
                    {t('sanctions.queueTitle')}
                </h1>
                <div className="flex items-center gap-2 text-sm text-slate-600" aria-live="polite">
                    {livePulse && <span className="badge badge-info">● Live</span>}
                    <button
                        className="btn-secondary text-sm py-1"
                        onClick={() => setRefreshTick((n) => n + 1)}
                    >
                        {t('common.retry')}
                    </button>
                </div>
            </header>

            {stalest && (stalest.staleHours ?? 0) > 24 && (
                <div
                    role="alert"
                    className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"
                >
                    Sanctions list <strong>{stalest.displayName}</strong> is{' '}
                    {stalest.staleHours} h stale (version {stalest.version}). Consider a
                    refresh.
                </div>
            )}

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-4">
                <CounterCard label={t('fraud.openTotal')} value={counters.openTotal} tone="info" />
                <CounterCard
                    label={t('fraud.slaBreached')}
                    value={counters.slaBreached}
                    tone={counters.slaBreached > 0 ? 'danger' : 'neutral'}
                />
            </div>

            <div className="bg-white rounded-lg shadow-sm mt-4 p-4">
                <div className="flex flex-wrap gap-3 items-end">
                    <fieldset className="flex flex-wrap gap-2">
                        <legend className="text-sm font-medium text-slate-700 mb-1">Status</legend>
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

                    <label className="text-sm">
                        <span className="block text-slate-700 mb-1">{t('sanctions.matchedList')}</span>
                        <select
                            className="input"
                            value={list}
                            onChange={(e) => updateParams({ list: e.target.value, page: '0' })}
                        >
                            <option value="">—</option>
                            {(lists ?? []).map((l) => (
                                <option key={l.listId} value={l.listId}>
                                    {l.displayName}
                                </option>
                            ))}
                        </select>
                    </label>

                    <label className="text-sm flex-1 min-w-[12rem]">
                        <span className="block text-slate-700 mb-1">
                            {t('sanctions.matchedEntity')}
                        </span>
                        <input
                            type="search"
                            className="input"
                            value={matchedEntity}
                            onChange={(e) =>
                                updateParams({ matchedEntity: e.target.value, page: '0' })
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
                                {t('sanctions.matchScore')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Status
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('sanctions.matchedEntity')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('sanctions.matchedList')}
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
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {!loading && (data?.items.length ?? 0) === 0 && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('accounts.noResults')}
                                </td>
                            </tr>
                        )}
                        {!loading &&
                            data?.items.map((it) => {
                                const score = typeof it.score === 'string' ? Number(it.score) : it.score;
                                const breached =
                                    it.slaBreached === true ||
                                    Date.parse(it.slaDeadline) < Date.now();
                                return (
                                    <tr
                                        key={it.hitId}
                                        className="border-t border-slate-100 hover:bg-slate-50"
                                    >
                                        <td className="px-3 py-2">
                                            <span className={scoreClass(score)}>
                                                {Number.isFinite(score) ? score.toFixed(2) : '—'}
                                            </span>
                                        </td>
                                        <td className="px-3 py-2">
                                            <span className={statusBadgeClass(it.status)}>
                                                {it.status}
                                            </span>
                                        </td>
                                        <td className="px-3 py-2">
                                            <Link
                                                to={`/sanctions/${encodeURIComponent(it.hitId)}`}
                                                className="text-jrpay-navy underline-offset-2 hover:underline"
                                            >
                                                {it.matchedEntityName}
                                            </Link>
                                            {it.beneficiaryNameOnTransaction && (
                                                <p className="text-xs text-slate-500">
                                                    on tx: {it.beneficiaryNameOnTransaction}
                                                </p>
                                            )}
                                        </td>
                                        <td className="px-3 py-2 font-mono text-xs">
                                            {it.matchedList}
                                        </td>
                                        <td className="px-3 py-2 text-xs">
                                            {it.assignee ?? <span className="text-slate-400">—</span>}
                                        </td>
                                        <td className="px-3 py-2 text-xs">
                                            <span
                                                className={
                                                    breached
                                                        ? 'text-jrpay-red font-semibold'
                                                        : 'text-slate-600'
                                                }
                                            >
                                                {formatDateTime(it.slaDeadline, i18n.language)}
                                            </span>
                                        </td>
                                    </tr>
                                );
                            })}
                    </tbody>
                </table>
            </div>

            {data && data.page.totalPages > 1 && (
                <Pagination page={data.page} onChange={(p) => updateParams({ page: String(p) })} />
            )}
        </section>
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
        tone === 'danger' ? 'text-jrpay-red' : tone === 'info' ? 'text-jrpay-navy' : 'text-slate-700';
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
        <nav aria-label="pagination" className="flex items-center justify-end gap-2 mt-4 text-sm">
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

function scoreClass(score: number): string {
    const base = 'inline-block tabular-nums px-2 py-0.5 rounded text-sm font-semibold ';
    if (!Number.isFinite(score)) return base + 'bg-slate-200 text-slate-700';
    if (score >= 0.95) return base + 'bg-score-high text-white';
    if (score >= 0.85) return base + 'bg-score-medium text-slate-900';
    return base + 'bg-score-low text-white';
}

function statusBadgeClass(s: Status): string {
    if (s === 'CONFIRMED_SANCTION') return 'badge badge-high';
    if (s === 'FALSE_POSITIVE') return 'badge badge-low';
    if (s === 'RELEASE_FOR_FRAUD_REVIEW') return 'badge badge-medium';
    return 'badge badge-info';
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

function parseStatuses(raw: string[]): Status[] {
    return raw.filter((s): s is Status => (ALL_STATUSES as string[]).includes(s));
}

function parsePage(raw: string | null): number {
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0;
}
