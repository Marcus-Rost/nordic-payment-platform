// UC-G19 / UC-G20 / UC-G21 — notifications inbox + preferences.
//
// Inbox surfaces customer-visible events: fraud-hold, sanctions-block (with
// anti-tipping-off-friendly copy), payment outcomes, R2P arrivals, etc.
// Preferences let the customer toggle channels per category.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { formatDateTime } from '../../lib/format';

type Severity = 'INFO' | 'WARN' | 'CRITICAL';

interface NotificationItem {
    notificationId: string;
    category: string;
    title: string;
    body: string;
    severity: Severity;
    receivedAt: string;
    read: boolean;
    deepLink?: string;
}

type Channel = 'email' | 'sms' | 'push';
type Category = 'payments' | 'security' | 'marketing' | 'productUpdates';

interface Preferences {
    channels: Record<Category, Record<Channel, boolean>>;
}

const CATEGORIES: Category[] = ['payments', 'security', 'marketing', 'productUpdates'];
const CHANNELS: Channel[] = ['email', 'sms', 'push'];

export function NotificationsPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [items, setItems] = useState<NotificationItem[] | null>(null);
    const [prefs, setPrefs] = useState<Preferences | null>(null);
    const [refresh, setRefresh] = useState(0);

    useEffect(() => {
        api<{ items: NotificationItem[] }>('/api/v1/customer/notifications')
            .then((r) => setItems(r.items ?? []))
            .catch((e) => showProblem(e));
        api<Preferences>('/api/v1/customer/notifications/preferences')
            .then(setPrefs)
            .catch(() => setPrefs(defaultPrefs()));
    }, [refresh, showProblem]);

    async function markRead(n: NotificationItem) {
        try {
            await api(
                `/api/v1/customer/notifications/${encodeURIComponent(n.notificationId)}/read`,
                { method: 'POST' }
            );
            setRefresh((x) => x + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    async function markAllRead() {
        try {
            await api('/api/v1/customer/notifications/read-all', { method: 'POST' });
            showSuccess(t('notifications.markAllRead'));
            setRefresh((x) => x + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    async function togglePref(cat: Category, ch: Channel, value: boolean) {
        if (!prefs) return;
        const next: Preferences = {
            ...prefs,
            channels: {
                ...prefs.channels,
                [cat]: { ...prefs.channels[cat], [ch]: value },
            },
        };
        setPrefs(next);
        try {
            await api('/api/v1/customer/notifications/preferences', {
                method: 'PUT',
                body: next,
            });
            showSuccess(t('common.save'));
        } catch (e) {
            showProblem(e);
            setRefresh((x) => x + 1);
        }
    }

    return (
        <section aria-labelledby="not-title">
            <header className="flex items-baseline justify-between flex-wrap gap-3">
                <h1 id="not-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('notifications.title')}
                </h1>
                <button className="btn-secondary text-sm" onClick={markAllRead}>
                    {t('notifications.markAllRead')}
                </button>
            </header>

            <div className="space-y-2 mt-4">
                {items === null && <p className="text-slate-500">{t('common.loading')}</p>}
                {items && items.length === 0 && (
                    <p className="text-slate-500">{t('common.noResults')}</p>
                )}
                {items?.map((n) => (
                    <article
                        key={n.notificationId}
                        className={`card p-4 flex items-start justify-between gap-3 ${
                            n.read ? 'opacity-70' : ''
                        }`}
                    >
                        <div>
                            <p className="font-semibold flex items-center gap-2">
                                <span className={severityClass(n.severity)}>{n.severity}</span>
                                {n.title}
                            </p>
                            <p className="text-sm text-slate-700 mt-1">{n.body}</p>
                            <p className="text-xs text-slate-500 mt-1">
                                {formatDateTime(n.receivedAt, i18n.language)}
                            </p>
                        </div>
                        {!n.read && (
                            <button
                                className="btn-secondary text-xs py-1"
                                onClick={() => markRead(n)}
                            >
                                {t('notifications.markRead')}
                            </button>
                        )}
                    </article>
                ))}
            </div>

            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700 mt-8 mb-2">
                {t('notifications.preferences')}
            </h2>
            <div className="card p-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('common.actions')}
                            </th>
                            {CHANNELS.map((c) => (
                                <th key={c} scope="col" className="text-center px-3 py-2">
                                    {t(`notifications.${c}`)}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {CATEGORIES.map((cat) => (
                            <tr key={cat} className="border-t border-slate-100">
                                <td className="px-3 py-2 font-medium">
                                    {t(`notifications.category${capitalise(cat)}`)}
                                </td>
                                {CHANNELS.map((ch) => (
                                    <td key={ch} className="px-3 py-2 text-center">
                                        <input
                                            type="checkbox"
                                            checked={prefs?.channels[cat]?.[ch] ?? false}
                                            onChange={(e) =>
                                                togglePref(cat, ch, e.target.checked)
                                            }
                                            aria-label={`${cat} ${ch}`}
                                        />
                                    </td>
                                ))}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </section>
    );
}

function severityClass(s: Severity): string {
    if (s === 'CRITICAL') return 'badge badge-danger';
    if (s === 'WARN') return 'badge badge-warn';
    return 'badge badge-info';
}

function capitalise(s: string): string {
    return s.charAt(0).toUpperCase() + s.slice(1);
}

function defaultPrefs(): Preferences {
    return {
        channels: {
            payments: { email: true, sms: true, push: true },
            security: { email: true, sms: true, push: true },
            marketing: { email: false, sms: false, push: false },
            productUpdates: { email: true, sms: false, push: false },
        },
    };
}
