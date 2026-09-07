// UC-G40 — audit search + chain integrity + export.
//
// Layout:
//   - filter form: range, type[], actor, resourceType, resourceId, outcome[],
//     severity[], free-text q.
//   - paginated results table with truncation banner at 10 000.
//   - chain integrity panel: green bars for verified ranges, red for gaps.
//     "Verify range" runs UC-G51 SCA (action=VERIFY_AUDIT_CHAIN).
//   - export controls: CSV / JSON_SIGNED kicked off async; status polled.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

type Outcome = 'SUCCESS' | 'FAILURE' | 'REJECTED';
type Severity = 'INFO' | 'WARN' | 'ERROR' | 'CRITICAL';
type ResourceType =
    | 'case'
    | 'hit'
    | 'account'
    | 'payment'
    | 'audit'
    | 'gdpr-request'
    | 'user'
    | 'banner';

interface AuditEventSummary {
    eventId: string;
    at: string;
    type: string;
    actor: string;
    outcome: Outcome;
    severity?: Severity;
    summary?: string;
    resourceType?: ResourceType;
    resourceId?: string;
    correlationId?: string;
    chainOffset?: number;
}

interface SearchResults {
    items: AuditEventSummary[];
    page: { number: number; size: number; totalElements: number; totalPages: number };
    truncated: boolean;
}

interface CheckpointView {
    ranges: {
        fromOffset: number;
        toOffset: number;
        hash: string;
        verifiedAt: string;
        verifiedBy: string;
        eventCount: number;
    }[];
    gaps: {
        fromOffset: number;
        toOffset: number;
        severity: Severity;
        reason: string;
        detectedAt: string;
    }[];
}

const PAGE_SIZE = 50;
const ALL_OUTCOMES: Outcome[] = ['SUCCESS', 'FAILURE', 'REJECTED'];
const ALL_SEVERITIES: Severity[] = ['INFO', 'WARN', 'ERROR', 'CRITICAL'];
const ALL_RESOURCES: ResourceType[] = [
    'case',
    'hit',
    'account',
    'payment',
    'audit',
    'gdpr-request',
    'user',
    'banner',
];

export function AuditSearchPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showInfo } = useToasts();

    const [from, setFrom] = useState('');
    const [to, setTo] = useState('');
    const [actor, setActor] = useState('');
    const [resourceType, setResourceType] = useState<ResourceType | ''>('');
    const [resourceId, setResourceId] = useState('');
    const [outcomes, setOutcomes] = useState<Outcome[]>([]);
    const [severities, setSeverities] = useState<Severity[]>([]);
    const [q, setQ] = useState('');
    const [page, setPage] = useState(0);
    const [results, setResults] = useState<SearchResults | null>(null);
    const [checkpoints, setCheckpoints] = useState<CheckpointView | null>(null);
    const [loading, setLoading] = useState(false);
    const [verifyOpen, setVerifyOpen] = useState(false);

    function toggleArr<T>(arr: T[], v: T): T[] {
        return arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v];
    }

    async function runSearch(targetPage = 0) {
        setLoading(true);
        try {
            const res = await api<SearchResults>('/api/v1/admin/audit/events', {
                query: {
                    from: from || undefined,
                    to: to || undefined,
                    actor: actor || undefined,
                    resourceType: resourceType || undefined,
                    resourceId: resourceId || undefined,
                    outcome: outcomes.length ? outcomes : undefined,
                    severity: severities.length ? severities : undefined,
                    q: q || undefined,
                    page: targetPage,
                    size: PAGE_SIZE,
                },
            });
            setResults(res);
            setPage(targetPage);
        } catch (e) {
            showProblem(e);
        } finally {
            setLoading(false);
        }
    }

    useEffect(() => {
        api<CheckpointView>('/api/v1/admin/audit/checkpoints')
            .then(setCheckpoints)
            .catch(() => setCheckpoints({ ranges: [], gaps: [] }));
    }, []);

    function onSubmit(e: React.FormEvent) {
        e.preventDefault();
        void runSearch(0);
    }

    async function exportBundle(format: 'CSV' | 'JSON_SIGNED') {
        try {
            const res = await api<{ requestId: string; status: string }>(
                '/api/v1/admin/audit/export',
                {
                    method: 'POST',
                    body: {
                        format,
                        filter: {
                            from: from || undefined,
                            to: to || undefined,
                            actor: actor || undefined,
                            resourceType: resourceType || undefined,
                            resourceId: resourceId || undefined,
                            outcome: outcomes.length ? outcomes : undefined,
                            severity: severities.length ? severities : undefined,
                            q: q || undefined,
                        },
                    },
                }
            );
            showInfo(`Export queued (${format})`, `Request id ${res.requestId}`);
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="audit-title">
            <h1 id="audit-title" className="text-2xl font-semibold text-jrpay-navy">
                {t('audit.searchTitle')}
            </h1>

            <form
                onSubmit={onSubmit}
                className="bg-white rounded-lg shadow-sm mt-4 p-4 space-y-3 text-sm"
            >
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                    <label>
                        From
                        <input
                            type="datetime-local"
                            className="input mt-1"
                            value={from}
                            onChange={(e) => setFrom(localToIso(e.target.value))}
                        />
                    </label>
                    <label>
                        To
                        <input
                            type="datetime-local"
                            className="input mt-1"
                            value={isoToLocal(to)}
                            onChange={(e) => setTo(localToIso(e.target.value))}
                        />
                    </label>
                    <label>
                        {t('audit.actor')}
                        <input
                            className="input mt-1"
                            value={actor}
                            onChange={(e) => setActor(e.target.value)}
                            placeholder="operator:abc / customer:123"
                        />
                    </label>
                    <label>
                        {t('audit.resource')}
                        <select
                            className="input mt-1"
                            value={resourceType}
                            onChange={(e) => setResourceType(e.target.value as ResourceType | '')}
                        >
                            <option value="">—</option>
                            {ALL_RESOURCES.map((r) => (
                                <option key={r} value={r}>
                                    {r}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label>
                        Resource id
                        <input
                            className="input mt-1"
                            value={resourceId}
                            onChange={(e) => setResourceId(e.target.value)}
                        />
                    </label>
                    <label className="lg:col-span-3">
                        {t('common.search')}
                        <input
                            className="input mt-1"
                            value={q}
                            onChange={(e) => setQ(e.target.value)}
                            placeholder="summary / actor / resourceId"
                        />
                    </label>
                </div>

                <fieldset className="flex flex-wrap gap-1">
                    <legend className="text-xs uppercase text-slate-500">Outcome</legend>
                    {ALL_OUTCOMES.map((o) => (
                        <label
                            key={o}
                            className={`px-2 py-0.5 rounded-full text-xs cursor-pointer border ${
                                outcomes.includes(o)
                                    ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                    : 'bg-white text-slate-700 border-slate-300'
                            }`}
                        >
                            <input
                                type="checkbox"
                                className="sr-only"
                                checked={outcomes.includes(o)}
                                onChange={() => setOutcomes((s) => toggleArr(s, o))}
                            />
                            {o}
                        </label>
                    ))}
                </fieldset>
                <fieldset className="flex flex-wrap gap-1">
                    <legend className="text-xs uppercase text-slate-500">Severity</legend>
                    {ALL_SEVERITIES.map((o) => (
                        <label
                            key={o}
                            className={`px-2 py-0.5 rounded-full text-xs cursor-pointer border ${
                                severities.includes(o)
                                    ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                    : 'bg-white text-slate-700 border-slate-300'
                            }`}
                        >
                            <input
                                type="checkbox"
                                className="sr-only"
                                checked={severities.includes(o)}
                                onChange={() => setSeverities((s) => toggleArr(s, o))}
                            />
                            {o}
                        </label>
                    ))}
                </fieldset>

                <div className="flex flex-wrap gap-2 justify-end">
                    <button
                        type="button"
                        className="btn-secondary text-sm"
                        onClick={() => exportBundle('CSV')}
                    >
                        Export CSV
                    </button>
                    <button
                        type="button"
                        className="btn-secondary text-sm"
                        onClick={() => exportBundle('JSON_SIGNED')}
                    >
                        Export signed JSON
                    </button>
                    <button type="submit" className="btn-primary" disabled={loading}>
                        {t('common.search')}
                    </button>
                </div>
            </form>

            <ChainPanel
                checkpoints={checkpoints}
                onVerify={() => setVerifyOpen(true)}
                locale={i18n.language}
            />

            {results?.truncated && (
                <div
                    role="alert"
                    className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"
                >
                    Result set exceeds 10 000 — narrow filters or use export.
                </div>
            )}

            <div className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                When
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('audit.type')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('audit.actor')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('audit.resource')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Outcome
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Severity
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Summary
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading && (
                            <tr>
                                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {!loading && (results?.items.length ?? 0) === 0 && results && (
                            <tr>
                                <td colSpan={7} className="px-3 py-6 text-center text-slate-500">
                                    {t('accounts.noResults')}
                                </td>
                            </tr>
                        )}
                        {!loading &&
                            results?.items.map((it) => (
                                <tr key={it.eventId} className="border-t border-slate-100 hover:bg-slate-50">
                                    <td className="px-3 py-2 text-xs tabular-nums">
                                        {formatDateTime(it.at, i18n.language)}
                                    </td>
                                    <td className="px-3 py-2 font-mono text-xs">{it.type}</td>
                                    <td className="px-3 py-2 text-xs">{it.actor}</td>
                                    <td className="px-3 py-2 text-xs">
                                        {it.resourceType && (
                                            <span className="badge badge-neutral">
                                                {it.resourceType}
                                            </span>
                                        )}
                                        {it.resourceId && (
                                            <code className="ml-2 text-xs">{it.resourceId}</code>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-xs">
                                        <span className={outcomeClass(it.outcome)}>
                                            {it.outcome}
                                        </span>
                                    </td>
                                    <td className="px-3 py-2 text-xs">
                                        {it.severity && (
                                            <span className={severityClass(it.severity)}>
                                                {it.severity}
                                            </span>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-xs text-slate-700">
                                        {it.summary}
                                    </td>
                                </tr>
                            ))}
                    </tbody>
                </table>
            </div>

            {results && results.page.totalPages > 1 && (
                <nav
                    aria-label="pagination"
                    className="flex items-center justify-end gap-2 mt-4 text-sm"
                >
                    <button
                        className="btn-secondary py-1"
                        onClick={() => runSearch(Math.max(0, page - 1))}
                        disabled={page === 0}
                    >
                        {t('common.prev')}
                    </button>
                    <span aria-live="polite">
                        {t('common.page')} {page + 1} {t('common.of')} {results.page.totalPages}
                    </span>
                    <button
                        className="btn-secondary py-1"
                        onClick={() => runSearch(Math.min(results.page.totalPages - 1, page + 1))}
                        disabled={page >= results.page.totalPages - 1}
                    >
                        {t('common.next')}
                    </button>
                </nav>
            )}

            {verifyOpen && checkpoints && (
                <VerifyChainModal
                    checkpoints={checkpoints}
                    onClose={() => setVerifyOpen(false)}
                    onDone={(res) => {
                        setVerifyOpen(false);
                        if (res.verified) {
                            showInfo(
                                'Hash chain verified',
                                `${res.eventCount ?? 0} events in ${res.durationMs} ms`
                            );
                        } else {
                            showProblem(
                                new Error(
                                    `Hash-chain mismatch at offset ${res.firstMismatchDiff?.offset ?? '?'}`
                                )
                            );
                        }
                    }}
                />
            )}
        </section>
    );
}

function ChainPanel({
    checkpoints,
    onVerify,
    locale,
}: {
    checkpoints: CheckpointView | null;
    onVerify: () => void;
    locale: string;
}) {
    const { t } = useTranslation();
    if (!checkpoints) return null;
    const allOffsets = [
        ...checkpoints.ranges.flatMap((r) => [r.fromOffset, r.toOffset]),
        ...checkpoints.gaps.flatMap((g) => [g.fromOffset, g.toOffset]),
    ];
    const min = allOffsets.length ? Math.min(...allOffsets) : 0;
    const max = allOffsets.length ? Math.max(...allOffsets) : 1;
    const span = Math.max(1, max - min);
    return (
        <section className="bg-white rounded-lg shadow-sm mt-4 p-4">
            <header className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide">
                    Hash chain
                </h2>
                <button className="btn-secondary text-sm py-1" onClick={onVerify}>
                    {t('audit.verifyChain')}
                </button>
            </header>
            <div className="relative h-6 bg-slate-100 rounded">
                {checkpoints.ranges.map((r, i) => (
                    <span
                        key={`r-${i}`}
                        className="absolute top-0 bottom-0 bg-jrpay-green/80"
                        title={`${r.fromOffset}–${r.toOffset} (${r.eventCount} events)`}
                        style={{
                            left: `${((r.fromOffset - min) / span) * 100}%`,
                            width: `${Math.max(0.5, ((r.toOffset - r.fromOffset) / span) * 100)}%`,
                        }}
                    />
                ))}
                {checkpoints.gaps.map((g, i) => (
                    <span
                        key={`g-${i}`}
                        className="absolute top-0 bottom-0 bg-jrpay-red/80"
                        title={`gap ${g.fromOffset}–${g.toOffset} (${g.reason})`}
                        style={{
                            left: `${((g.fromOffset - min) / span) * 100}%`,
                            width: `${Math.max(0.5, ((g.toOffset - g.fromOffset) / span) * 100)}%`,
                        }}
                    />
                ))}
            </div>
            <p className="text-xs text-slate-500 mt-2">
                {checkpoints.ranges.length} verified ranges · {checkpoints.gaps.length} gaps · offsets{' '}
                {min}–{max}
            </p>
            {checkpoints.gaps.length > 0 && (
                <ul className="mt-2 text-xs space-y-1">
                    {checkpoints.gaps.slice(0, 5).map((g, i) => (
                        <li key={i} className="text-jrpay-red">
                            Gap {g.fromOffset}–{g.toOffset} ({g.reason},{' '}
                            {formatDateTime(g.detectedAt, locale)})
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

function VerifyChainModal({
    checkpoints,
    onClose,
    onDone,
}: {
    checkpoints: CheckpointView;
    onClose: () => void;
    onDone: (res: VerifyAck) => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const offsets = [
        ...checkpoints.ranges.flatMap((r) => [r.fromOffset, r.toOffset]),
        ...checkpoints.gaps.flatMap((g) => [g.fromOffset, g.toOffset]),
    ];
    const min = offsets.length ? Math.min(...offsets) : 0;
    const max = offsets.length ? Math.max(...offsets) : 0;
    const [fromOffset, setFromOffset] = useState(min);
    const [toOffset, setToOffset] = useState(max);
    const [reason, setReason] = useState('');
    const [scaOpen, setScaOpen] = useState(false);
    const [hash, setHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        setSubmitting(true);
        try {
            const h = await sha256HexNfc(`${fromOffset}-${toOffset}:${reason}`);
            setHash(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            const res = await api<VerifyAck>('/api/v1/admin/audit/verify', {
                method: 'POST',
                stepUpToken,
                body: { fromOffset, toOffset, reason: reason || undefined },
            });
            onDone(res);
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    if (scaOpen && hash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'VERIFY_AUDIT_CHAIN',
                    resourceType: 'audit',
                    resourceId: `${fromOffset}-${toOffset}`,
                    notesHash: hash,
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
        <Modal titleId="verify-title" onClose={onClose}>
            <h2 id="verify-title" className="text-lg font-semibold text-jrpay-navy">
                {t('audit.verifyChain')}
            </h2>
            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <div className="grid grid-cols-2 gap-3">
                    <label>
                        From offset
                        <input
                            type="number"
                            min={0}
                            className="input mt-1 tabular-nums"
                            value={fromOffset}
                            onChange={(e) => setFromOffset(Math.max(0, Number(e.target.value) || 0))}
                        />
                    </label>
                    <label>
                        To offset
                        <input
                            type="number"
                            min={0}
                            className="input mt-1 tabular-nums"
                            value={toOffset}
                            onChange={(e) => setToOffset(Math.max(0, Number(e.target.value) || 0))}
                        />
                    </label>
                </div>
                <label className="block">
                    Reason (optional)
                    <input
                        className="input mt-1"
                        maxLength={280}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
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
                        disabled={submitting || toOffset <= fromOffset}
                    >
                        {t('audit.verifyChain')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}

interface VerifyAck {
    verified: boolean;
    fromOffset: number;
    toOffset: number;
    eventCount?: number;
    durationMs: number;
    mismatchOffsets?: number[];
    firstMismatchDiff?: { offset: number; expectedHash: string; actualHash: string };
}

function outcomeClass(o: Outcome): string {
    if (o === 'SUCCESS') return 'badge badge-low';
    if (o === 'FAILURE') return 'badge badge-high';
    return 'badge badge-medium';
}

function severityClass(s: Severity): string {
    if (s === 'CRITICAL' || s === 'ERROR') return 'badge badge-high';
    if (s === 'WARN') return 'badge badge-medium';
    return 'badge badge-info';
}

function localToIso(local: string): string {
    if (!local) return '';
    const d = new Date(local);
    return Number.isFinite(d.getTime()) ? d.toISOString() : '';
}

function isoToLocal(iso: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (!Number.isFinite(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
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
