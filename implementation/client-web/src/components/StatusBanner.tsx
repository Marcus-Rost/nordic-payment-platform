// UC-G54 — system-wide status banner. Customer surface subscribes to
// /api/v1/public/status/stream (unauthenticated SSE, audience=CUSTOMER) and
// renders the most-severe active banner at the top of the AppShell.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

export type StatusBannerSeverity = 'info' | 'warn' | 'maintenance' | 'incident';

export interface StatusBannerEntry {
    id: string;
    severity: StatusBannerSeverity;
    title: string;
    body?: string;
    publishedAt?: string;
}

const SEVERITY_CLASS: Record<StatusBannerSeverity, string> = {
    info: 'bg-blue-100 text-blue-900 border-blue-200',
    warn: 'bg-amber-100 text-amber-900 border-amber-200',
    maintenance: 'bg-slate-100 text-slate-900 border-slate-200',
    incident: 'bg-red-100 text-red-900 border-red-200',
};

const SEVERITY_RANK: Record<StatusBannerSeverity, number> = {
    info: 0,
    maintenance: 1,
    warn: 2,
    incident: 3,
};

interface PublicBannersPayload {
    banners?: {
        id: string;
        severity: string;
        title?: string;
        titleKey?: string;
        body?: string;
        bodyKey?: string;
        publishedAt?: string;
    }[];
}

export function StatusBanner({ banner }: { banner?: StatusBannerEntry | null }) {
    const { t } = useTranslation();
    const [live, setLive] = useState<StatusBannerEntry | null>(banner ?? null);

    useEffect(() => {
        if (banner !== undefined) return; // controlled mode
        let cancelled = false;
        let source: EventSource | null = null;

        async function init() {
            try {
                const res = await fetch('/api/v1/public/status', {
                    headers: { 'X-Audience': 'CUSTOMER' },
                });
                if (cancelled) return;
                if (res.ok) {
                    const body = (await res.json()) as PublicBannersPayload;
                    setLive(toEntry(body.banners ?? []));
                }
            } catch {
                /* ignore */
            }
            try {
                source = new EventSource('/api/v1/public/status/stream');
                const handler = (ev: MessageEvent) => {
                    try {
                        const body = JSON.parse(ev.data) as PublicBannersPayload;
                        setLive(toEntry(body.banners ?? []));
                    } catch {
                        /* ignore */
                    }
                };
                source.addEventListener('banner.published', handler);
                source.addEventListener('banner.updated', handler);
                source.addEventListener('banner.resolved', handler);
            } catch {
                /* ignore */
            }
        }
        void init();
        return () => {
            cancelled = true;
            source?.close();
        };
    }, [banner]);

    const active = banner ?? live;
    if (!active) return null;
    return (
        <div
            role="status"
            aria-live="polite"
            className={`border-b px-4 py-2 text-sm ${SEVERITY_CLASS[active.severity]}`}
        >
            <strong>{active.title}</strong>
            {active.body && <span className="ml-2">{active.body}</span>}
            {active.publishedAt && (
                <span className="ml-2 opacity-75">
                    {t('common.details')}: {active.publishedAt}
                </span>
            )}
        </div>
    );
}

function toEntry(items: PublicBannersPayload['banners']): StatusBannerEntry | null {
    if (!items || items.length === 0) return null;
    let best: { entry: StatusBannerEntry; rank: number } | null = null;
    for (const it of items) {
        const sev = normaliseSeverity(it.severity);
        const entry: StatusBannerEntry = {
            id: it.id,
            severity: sev,
            title: it.title ?? it.titleKey ?? '',
            body: it.body ?? it.bodyKey,
            publishedAt: it.publishedAt,
        };
        const rank = SEVERITY_RANK[sev];
        if (!best || rank > best.rank) best = { entry, rank };
    }
    return best?.entry ?? null;
}

function normaliseSeverity(s: string): StatusBannerSeverity {
    const v = (s ?? '').toLowerCase();
    if (v === 'incident') return 'incident';
    if (v === 'warn' || v === 'warning') return 'warn';
    if (v === 'maintenance') return 'maintenance';
    return 'info';
}
