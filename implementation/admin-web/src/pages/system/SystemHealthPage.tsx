// UC-G42 — system health + queues + SLA tiles + DLQ.
//
// Read-only dashboard refreshed every 15 s.
// DLQ retry/purge are SUPERVISOR+; UI shows the buttons but the server
// enforces the role and returns 403 if the operator is not eligible.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';

type State = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';
type SlaStatus = 'GREEN' | 'AMBER' | 'RED';

interface ServiceHealth {
    name: string;
    state: State;
    errorRate1m?: number;
    p50LatencyMs?: number;
    p95LatencyMs?: number;
    p99LatencyMs?: number;
    uptimeSeconds?: number;
    grafanaUrl?: string;
}

interface SystemHealth {
    asOf: string;
    staleAt?: string;
    services: ServiceHealth[];
}

interface QueueSnapshot {
    asOf: string;
    topics: { topic: string; lag: number; threshold: number; alert: boolean }[];
}

interface SlaTile {
    id: string;
    label: string;
    current: number;
    target: number;
    unit: 'SECONDS' | 'MINUTES' | 'HOURS';
    status: SlaStatus;
    drillThroughHref?: string;
}

interface SlaSnapshot {
    asOf: string;
    tiles: SlaTile[];
}

interface DlqSample {
    id: string;
    firstSeenAt: string;
    lastRetryAt: string;
    retryCount: number;
    failureReason: string;
    eligibleForPurge?: boolean;
}

interface DlqSnapshot {
    asOf: string;
    topics: { topic: string; depth: number; samples?: DlqSample[] }[];
}

const REFRESH_INTERVAL = 15_000;

export function SystemHealthPage() {
    const { t, i18n } = useTranslation();
    const { showProblem } = useToasts();

    const [health, setHealth] = useState<SystemHealth | null>(null);
    const [queues, setQueues] = useState<QueueSnapshot | null>(null);
    const [sla, setSla] = useState<SlaSnapshot | null>(null);
    const [dlq, setDlq] = useState<DlqSnapshot | null>(null);

    useEffect(() => {
        let stop = false;
        async function fetchAll() {
            const [h, q, s, d] = await Promise.allSettled([
                api<SystemHealth>('/api/v1/admin/system/health'),
                api<QueueSnapshot>('/api/v1/admin/system/queues'),
                api<SlaSnapshot>('/api/v1/admin/system/sla'),
                api<DlqSnapshot>('/api/v1/admin/system/dlq'),
            ]);
            if (stop) return;
            if (h.status === 'fulfilled') setHealth(h.value);
            if (q.status === 'fulfilled') setQueues(q.value);
            if (s.status === 'fulfilled') setSla(s.value);
            if (d.status === 'fulfilled') setDlq(d.value);
            const errs = [h, q, s, d].filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
            for (const e of errs.slice(0, 1)) showProblem(e.reason);
        }
        void fetchAll();
        const id = setInterval(fetchAll, REFRESH_INTERVAL);
        return () => {
            stop = true;
            clearInterval(id);
        };
    }, [showProblem]);

    return (
        <section aria-labelledby="system-title">
            <header className="flex items-baseline justify-between">
                <h1 id="system-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('system.title')}
                </h1>
                {health && (
                    <p className="text-xs text-slate-500">
                        As of {formatDateTime(health.asOf, i18n.language)}
                        {health.staleAt && ' · cached (Prometheus unavailable)'}
                    </p>
                )}
            </header>

            <h2 className="sr-only">SLA tiles</h2>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-4">
                {(sla?.tiles ?? []).map((tile) => (
                    <SlaCard key={tile.id} tile={tile} />
                ))}
            </div>

            <section className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide px-4 pt-4">
                    {t('system.service')}
                </h2>
                <table className="w-full text-sm mt-2">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('system.service')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('system.status')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                err 1m
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                p50
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                p95
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                p99
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {(health?.services ?? []).map((s) => (
                            <tr key={s.name} className="border-t border-slate-100">
                                <td className="px-3 py-2 font-medium">{s.name}</td>
                                <td className="px-3 py-2">
                                    <span className={stateClass(s.state)}>{s.state}</span>
                                </td>
                                <td className="px-3 py-2 tabular-nums">
                                    {s.errorRate1m != null
                                        ? `${(s.errorRate1m * 100).toFixed(2)} %`
                                        : '—'}
                                </td>
                                <td className="px-3 py-2 tabular-nums">
                                    {s.p50LatencyMs ?? '—'} ms
                                </td>
                                <td className="px-3 py-2 tabular-nums">
                                    {s.p95LatencyMs ?? '—'} ms
                                </td>
                                <td className="px-3 py-2 tabular-nums">
                                    {s.p99LatencyMs ?? '—'} ms
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </section>

            <section className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide px-4 pt-4">
                    Kafka queues
                </h2>
                <table className="w-full text-sm mt-2">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                Topic
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Lag
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Threshold
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {(queues?.topics ?? []).map((q) => (
                            <tr key={q.topic} className="border-t border-slate-100">
                                <td className="px-3 py-2 font-mono text-xs">{q.topic}</td>
                                <td
                                    className={`px-3 py-2 tabular-nums ${q.alert ? 'text-jrpay-red font-semibold' : ''}`}
                                >
                                    {q.lag}
                                </td>
                                <td className="px-3 py-2 tabular-nums text-slate-500">
                                    {q.threshold}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </section>

            <DlqSection dlq={dlq} locale={i18n.language} />
        </section>
    );
}

function SlaCard({ tile }: { tile: SlaTile }) {
    const cls =
        tile.status === 'GREEN'
            ? 'border-jrpay-green text-jrpay-green'
            : tile.status === 'AMBER'
              ? 'border-jrpay-amber text-amber-700'
              : 'border-jrpay-red text-jrpay-red';
    return (
        <div className={`bg-white rounded-lg shadow-sm border-l-4 p-4 ${cls}`}>
            <p className="text-xs uppercase tracking-wide text-slate-500">{tile.label}</p>
            <p className="text-2xl font-semibold tabular-nums mt-1">
                {tile.current.toFixed(1)} {unitLabel(tile.unit)}
            </p>
            <p className="text-xs text-slate-500 mt-1">
                Target ≤ {tile.target} {unitLabel(tile.unit)}
            </p>
            {tile.drillThroughHref && (
                <a
                    href={tile.drillThroughHref}
                    className="text-xs text-jrpay-navy hover:underline mt-2 inline-block"
                >
                    Open offending records →
                </a>
            )}
        </div>
    );
}

function DlqSection({ dlq, locale }: { dlq: DlqSnapshot | null; locale: string }) {
    const [expanded, setExpanded] = useState<string | null>(null);
    if (!dlq) return null;
    return (
        <section className="bg-white rounded-lg shadow-sm mt-4">
            <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide px-4 pt-4">
                DLQ
            </h2>
            <ul className="text-sm mt-2 divide-y divide-slate-100">
                {dlq.topics.map((t) => (
                    <li key={t.topic}>
                        <button
                            className="w-full flex justify-between px-4 py-2 text-left hover:bg-slate-50"
                            onClick={() => setExpanded((e) => (e === t.topic ? null : t.topic))}
                            aria-expanded={expanded === t.topic}
                        >
                            <span className="font-mono text-xs">{t.topic}</span>
                            <span
                                className={
                                    t.depth > 0
                                        ? 'tabular-nums text-jrpay-red font-semibold'
                                        : 'tabular-nums text-slate-500'
                                }
                            >
                                {t.depth}
                            </span>
                        </button>
                        {expanded === t.topic && t.samples && t.samples.length > 0 && (
                            <table className="w-full text-xs px-4 pb-3">
                                <thead className="text-slate-500">
                                    <tr>
                                        <th scope="col" className="text-left px-3 py-1">
                                            Id
                                        </th>
                                        <th scope="col" className="text-left px-3 py-1">
                                            First seen
                                        </th>
                                        <th scope="col" className="text-left px-3 py-1">
                                            Retries
                                        </th>
                                        <th scope="col" className="text-left px-3 py-1">
                                            Reason
                                        </th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {t.samples.map((s) => (
                                        <tr key={s.id} className="border-t border-slate-100">
                                            <td className="px-3 py-1 font-mono">{s.id}</td>
                                            <td className="px-3 py-1">
                                                {formatDateTime(s.firstSeenAt, locale)}
                                            </td>
                                            <td className="px-3 py-1 tabular-nums">
                                                {s.retryCount}
                                                {s.eligibleForPurge && (
                                                    <span className="ml-2 badge badge-medium">
                                                        purgeable
                                                    </span>
                                                )}
                                            </td>
                                            <td className="px-3 py-1">{s.failureReason}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </li>
                ))}
            </ul>
            <p className="text-xs text-slate-500 px-4 pb-3">
                Retry / purge actions require SUPERVISOR — surfaced inline on each entry once
                connected to the DLQ retry SCA flow.
            </p>
        </section>
    );
}

function stateClass(s: State): string {
    if (s === 'UP') return 'badge badge-low';
    if (s === 'DEGRADED') return 'badge badge-medium';
    if (s === 'DOWN') return 'badge badge-high';
    return 'badge badge-neutral';
}

function unitLabel(u: 'SECONDS' | 'MINUTES' | 'HOURS'): string {
    return u === 'SECONDS' ? 's' : u === 'MINUTES' ? 'min' : 'h';
}

function formatDateTime(iso: string, locale: string): string {
    try {
        return new Intl.DateTimeFormat(locale, {
            dateStyle: 'short',
            timeStyle: 'medium',
        }).format(new Date(iso));
    } catch {
        return iso;
    }
}
