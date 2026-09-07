// UC-G54 — system-wide status banner displayed at the top of the AppShell.
// Driven by an SSE feed in the running stack (status.events topic, fanned
// out by the status-banner service). For Phase 3 we render a static "all
// systems normal" by default and let admin/system view inject overrides.

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

export function StatusBanner({ banner }: { banner: StatusBannerEntry | null }) {
    const { t } = useTranslation();
    if (!banner) return null;
    return (
        <div
            role="status"
            aria-live="polite"
            className={`border-b px-4 py-2 text-sm ${SEVERITY_CLASS[banner.severity]}`}
        >
            <strong>{banner.title}</strong>
            {banner.body && <span className="ml-2">{banner.body}</span>}
            {banner.publishedAt && (
                <span className="ml-2 opacity-75">{t('common.details')}: {banner.publishedAt}</span>
            )}
        </div>
    );
}
