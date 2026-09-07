// UC-G24 / UC-G25 / UC-G26 — GDPR + privacy.
//
// Three sections:
//   - Data export (request + poll status + download signed-URL bundle)
//   - Account erasure (request with the gdpr-action SCA flow)
//   - Consent log (read-only, paginated)
//   - Marketing-consent toggle (immediate)

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { useAuth } from '../../auth/AuthContext';
import { ScaModal } from '../../sca/ScaModal';
import { gdprActionDlHash, sha256HexNfc } from '../../sca/dlHash';
import { Modal } from '../../components/Modal';
import { formatDateTime } from '../../lib/format';

interface ExportRequest {
    requestId: string;
    status: 'PENDING' | 'IN_PROGRESS' | 'READY' | 'FAILED' | 'EXPIRED';
    requestedAt: string;
    readyAt?: string;
    downloadUrl?: string;
    expiresAt?: string;
}

interface ConsentLogEntry {
    eventId: string;
    at: string;
    type: string;
    summary?: string;
}

interface PrivacySettings {
    marketingConsent: boolean;
    erasureRequestedAt?: string;
}

export function PrivacyPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const { customerId } = useAuth();
    const [exports, setExports] = useState<ExportRequest[] | null>(null);
    const [log, setLog] = useState<ConsentLogEntry[] | null>(null);
    const [settings, setSettings] = useState<PrivacySettings | null>(null);
    const [refresh, setRefresh] = useState(0);
    const [erasureOpen, setErasureOpen] = useState(false);

    useEffect(() => {
        api<{ items: ExportRequest[] }>('/api/v1/customer/gdpr/exports')
            .then((r) => setExports(r.items ?? []))
            .catch(() => setExports([]));
        api<{ items: ConsentLogEntry[] }>('/api/v1/customer/privacy/consent-log', {
            query: { limit: 25 },
        })
            .then((r) => setLog(r.items ?? []))
            .catch(() => setLog([]));
        api<PrivacySettings>('/api/v1/customer/privacy/settings')
            .then(setSettings)
            .catch(() => setSettings({ marketingConsent: false }));
    }, [refresh]);

    async function requestExport() {
        try {
            await api('/api/v1/customer/gdpr/exports', {
                method: 'POST',
                body: { format: 'JSON_SIGNED' },
            });
            showSuccess(t('privacy.exportPending'));
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    async function toggleMarketing(value: boolean) {
        try {
            await api('/api/v1/customer/privacy/settings', {
                method: 'PATCH',
                body: { marketingConsent: value },
            });
            setSettings((s) => (s ? { ...s, marketingConsent: value } : s));
            showSuccess(t('common.save'));
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="priv-title">
            <h1 id="priv-title" className="text-2xl font-semibold text-jrpay-navy">
                {t('privacy.title')}
            </h1>

            <section className="card p-4 mt-4">
                <h2 className="font-semibold">{t('privacy.exportTitle')}</h2>
                <p className="text-sm text-slate-600 mt-1">{t('privacy.exportNotice')}</p>
                <button className="btn-primary mt-3" onClick={requestExport}>
                    {t('privacy.requestExport')}
                </button>
                {exports && exports.length > 0 && (
                    <ul className="mt-4 divide-y divide-slate-100 text-sm">
                        {exports.map((e) => (
                            <li
                                key={e.requestId}
                                className="py-2 flex items-center justify-between"
                            >
                                <div>
                                    <p className="font-mono text-xs">{e.requestId}</p>
                                    <p className="text-xs text-slate-500">
                                        {formatDateTime(e.requestedAt, i18n.language)} · {e.status}
                                    </p>
                                </div>
                                {e.status === 'READY' && e.downloadUrl && (
                                    <a
                                        href={e.downloadUrl}
                                        className="btn-accent text-xs py-1"
                                        rel="noopener noreferrer"
                                    >
                                        {t('privacy.downloadExport')}
                                    </a>
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </section>

            <section className="card p-4 mt-4">
                <h2 className="font-semibold">{t('privacy.erasureTitle')}</h2>
                <p className="text-sm text-slate-600 mt-1">{t('privacy.erasureNotice')}</p>
                {settings?.erasureRequestedAt ? (
                    <p className="badge badge-warn mt-3">
                        {t('privacy.erasureRequested')}:{' '}
                        {formatDateTime(settings.erasureRequestedAt, i18n.language)}
                    </p>
                ) : (
                    <button className="btn-danger mt-3" onClick={() => setErasureOpen(true)}>
                        {t('privacy.requestErasure')}
                    </button>
                )}
            </section>

            <section className="card p-4 mt-4">
                <h2 className="font-semibold">{t('privacy.marketingConsent')}</h2>
                <p className="text-sm text-slate-600 mt-1">
                    {t('privacy.marketingConsentBody')}
                </p>
                <label className="flex items-center gap-2 mt-3 text-sm cursor-pointer">
                    <input
                        type="checkbox"
                        checked={settings?.marketingConsent ?? false}
                        onChange={(e) => toggleMarketing(e.target.checked)}
                    />
                    <span>{t('common.confirm')}</span>
                </label>
            </section>

            <section className="card p-4 mt-4">
                <h2 className="font-semibold">{t('privacy.consentLogTitle')}</h2>
                {log === null && <p className="text-slate-500 mt-2">{t('common.loading')}</p>}
                {log && log.length === 0 && (
                    <p className="text-slate-500 mt-2">{t('common.noResults')}</p>
                )}
                {log && log.length > 0 && (
                    <ul className="mt-3 divide-y divide-slate-100 text-sm">
                        {log.map((e) => (
                            <li key={e.eventId} className="py-2">
                                <p className="font-mono text-xs">{e.type}</p>
                                <p className="text-xs text-slate-500">
                                    {formatDateTime(e.at, i18n.language)}
                                </p>
                                {e.summary && (
                                    <p className="text-sm text-slate-700 mt-1">{e.summary}</p>
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </section>

            {erasureOpen && customerId && (
                <ErasureModal
                    customerId={customerId}
                    onClose={() => setErasureOpen(false)}
                    onDone={() => {
                        setErasureOpen(false);
                        showSuccess(t('privacy.erasureRequested'));
                        setRefresh((n) => n + 1);
                    }}
                />
            )}
        </section>
    );
}

function ErasureModal({
    customerId,
    onClose,
    onDone,
}: {
    customerId: string;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [reason, setReason] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [scaOpen, setScaOpen] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [hex, setHex] = useState<string | null>(null);

    const valid = reason.length >= 20 && confirmed;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            const notesHash = await sha256HexNfc(reason);
            const { hex: h } = await gdprActionDlHash({
                action: 'ERASURE_REQUEST',
                customerId,
                notesHash,
            });
            setHex(h);
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            await api('/api/v1/customer/gdpr/erasure', {
                method: 'POST',
                stepUpToken,
                body: { reason, confirmed: true },
            });
            onDone();
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

    if (scaOpen && hex) {
        return (
            <ScaModal
                inputs={{
                    class: 'gdpr-action',
                    action: 'ERASURE_REQUEST',
                    customerId,
                    notesHash: hex,
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
        <Modal titleId="erasure-title" onClose={onClose}>
            <h2 id="erasure-title" className="text-lg font-semibold text-jrpay-navy">
                {t('privacy.erasureTitle')}
            </h2>
            <p className="text-sm text-slate-600 mt-2">{t('privacy.erasureNotice')}</p>
            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <label className="block">
                    Reason (≥ 20 chars)
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                    />
                </label>
                <p
                    className={
                        reason.length >= 20 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'
                    }
                >
                    {reason.length} / 20
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    <span>I confirm I want to permanently delete my account.</span>
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
                        className="btn-danger"
                        disabled={!valid || submitting}
                    >
                        {t('privacy.requestErasure')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}
