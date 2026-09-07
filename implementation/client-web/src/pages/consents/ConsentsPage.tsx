// UC-G17 / UC-G18 — view + revoke active PSD2 consents (AISP / PISP /
// CBPII / VRP). Revocation does not require SCA — it's a customer-protection
// action, audited but immediate.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { formatAmount, formatDate } from '../../lib/format';

type ConsentType = 'AISP' | 'PISP' | 'CBPII' | 'VRP';

interface Consent {
    consentId: string;
    consentType: ConsentType;
    tppId: string;
    tppName: string;
    accountIds?: string[];
    grantedAt: string;
    expiresAt: string;
    status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
    vrpDetails?: { capAmount: string; capCurrency: string; frequency: string };
}

export function ConsentsPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [items, setItems] = useState<Consent[] | null>(null);
    const [refresh, setRefresh] = useState(0);

    useEffect(() => {
        api<{ items: Consent[] }>('/api/v1/customer/consents')
            .then((r) => setItems(r.items ?? []))
            .catch((e) => showProblem(e));
    }, [refresh, showProblem]);

    async function revoke(c: Consent) {
        try {
            await api(`/api/v1/customer/consents/${encodeURIComponent(c.consentId)}`, {
                method: 'DELETE',
            });
            showSuccess(t('consents.revoke'));
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="cons-title">
            <h1 id="cons-title" className="text-2xl font-semibold text-jrpay-navy">
                {t('consents.title')}
            </h1>

            <div className="space-y-3 mt-4">
                {items === null && <p className="text-slate-500">{t('common.loading')}</p>}
                {items && items.length === 0 && (
                    <p className="text-slate-500">{t('consents.noConsents')}</p>
                )}
                {items?.map((c) => (
                    <article
                        key={c.consentId}
                        className="card p-4 flex flex-wrap items-start justify-between gap-3"
                    >
                        <div>
                            <h2 className="font-semibold">{c.tppName}</h2>
                            <p className="text-xs text-slate-500 mt-1">
                                {c.consentType} ·{' '}
                                {t('consents.expires')}: {formatDate(c.expiresAt, i18n.language)}
                            </p>
                            {c.accountIds && c.accountIds.length > 0 && (
                                <p className="text-xs text-slate-700 mt-1">
                                    {c.accountIds.join(', ')}
                                </p>
                            )}
                            {c.vrpDetails && (
                                <p className="text-xs text-slate-700 mt-1">
                                    {t('consents.vrpCap')}:{' '}
                                    {formatAmount(
                                        c.vrpDetails.capAmount,
                                        c.vrpDetails.capCurrency,
                                        i18n.language
                                    )}{' '}
                                    · {t('consents.vrpFrequency')}: {c.vrpDetails.frequency}
                                </p>
                            )}
                        </div>
                        <div className="flex gap-2 items-start">
                            <span
                                className={
                                    c.status === 'ACTIVE'
                                        ? 'badge badge-success'
                                        : 'badge badge-neutral'
                                }
                            >
                                {c.status}
                            </span>
                            {c.status === 'ACTIVE' && (
                                <button className="btn-danger text-xs py-1" onClick={() => revoke(c)}>
                                    {t('consents.revoke')}
                                </button>
                            )}
                        </div>
                    </article>
                ))}
            </div>
        </section>
    );
}
