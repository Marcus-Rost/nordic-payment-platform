// UC-G22 / UC-G23 — profile view + edit + language preference + SCA factor
// management. Edits are limited to phone, email, address, language and the
// marketing-consent toggle (see PrivacyPage for GDPR-grade consent management).

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { setLanguage } from '../../i18n/i18n';

interface Profile {
    customerId: string;
    name: string;
    email: string;
    phone?: string;
    address?: string;
    language?: 'nb-NO' | 'en-US';
    scaFactors: { type: string; addedAt: string }[];
}

export function ProfilePage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [profile, setProfile] = useState<Profile | null>(null);
    const [editing, setEditing] = useState(false);
    const [refresh, setRefresh] = useState(0);

    useEffect(() => {
        api<Profile>('/api/v1/customer/profile')
            .then((p) => {
                setProfile(p);
                if (p.language && p.language !== i18n.language) {
                    setLanguage(p.language);
                }
            })
            .catch((e) => showProblem(e));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refresh, showProblem]);

    async function save(form: Partial<Profile>) {
        try {
            await api('/api/v1/customer/profile', { method: 'PATCH', body: form });
            showSuccess(t('common.save'));
            setEditing(false);
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    async function removeFactor(type: string) {
        try {
            await api(`/api/v1/customer/profile/sca-factors/${encodeURIComponent(type)}`, {
                method: 'DELETE',
            });
            showSuccess(t('profile.removeFactor'));
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="profile-title">
            <h1 id="profile-title" className="text-2xl font-semibold text-jrpay-navy">
                {t('profile.title')}
            </h1>

            {profile === null ? (
                <p className="text-slate-500 mt-4">{t('common.loading')}</p>
            ) : null}

            {profile && !editing && (
                <div className="card p-4 mt-4 grid grid-cols-2 gap-y-2 text-sm">
                    <p className="text-slate-500">{t('profile.name')}</p>
                    <p>{profile.name}</p>
                    <p className="text-slate-500">{t('profile.email')}</p>
                    <p>{profile.email}</p>
                    <p className="text-slate-500">{t('profile.phone')}</p>
                    <p>{profile.phone ?? '—'}</p>
                    <p className="text-slate-500">{t('profile.address')}</p>
                    <p className="whitespace-pre-wrap">{profile.address ?? '—'}</p>
                    <p className="text-slate-500">{t('profile.language')}</p>
                    <p>{profile.language ?? i18n.language}</p>
                    <div className="col-span-2 flex justify-end mt-2">
                        <button className="btn-secondary" onClick={() => setEditing(true)}>
                            {t('common.edit')}
                        </button>
                    </div>
                </div>
            )}

            {profile && editing && (
                <EditForm
                    profile={profile}
                    onSave={save}
                    onCancel={() => setEditing(false)}
                />
            )}

            {profile && (
                <>
                    <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700 mt-8 mb-2">
                        {t('profile.scaFactors')}
                    </h2>
                    <div className="card p-4">
                        <ul className="divide-y divide-slate-100">
                            {profile.scaFactors.length === 0 && (
                                <li className="py-2 text-sm text-slate-500">
                                    {t('common.noResults')}
                                </li>
                            )}
                            {profile.scaFactors.map((f) => (
                                <li
                                    key={f.type}
                                    className="py-2 flex items-center justify-between text-sm"
                                >
                                    <span>
                                        <strong>{f.type}</strong>{' '}
                                        <span className="text-slate-500">{f.addedAt}</span>
                                    </span>
                                    {profile.scaFactors.length > 1 && (
                                        <button
                                            className="btn-danger text-xs py-1"
                                            onClick={() => removeFactor(f.type)}
                                        >
                                            {t('profile.removeFactor')}
                                        </button>
                                    )}
                                </li>
                            ))}
                        </ul>
                    </div>
                </>
            )}
        </section>
    );
}

function EditForm({
    profile,
    onSave,
    onCancel,
}: {
    profile: Profile;
    onSave: (form: Partial<Profile>) => void;
    onCancel: () => void;
}) {
    const { t } = useTranslation();
    const [phone, setPhone] = useState(profile.phone ?? '');
    const [address, setAddress] = useState(profile.address ?? '');
    const [language, setLanguagePref] = useState<'nb-NO' | 'en-US'>(
        profile.language ?? 'nb-NO'
    );

    return (
        <form
            className="card p-4 mt-4 space-y-3 text-sm"
            onSubmit={(e) => {
                e.preventDefault();
                onSave({ phone, address, language });
                setLanguage(language);
            }}
        >
            <label className="block">
                {t('profile.phone')}
                <input
                    type="tel"
                    className="input mt-1"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                />
            </label>
            <label className="block">
                {t('profile.address')}
                <textarea
                    className="input mt-1"
                    rows={3}
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                />
            </label>
            <label className="block">
                {t('profile.language')}
                <select
                    className="input mt-1"
                    value={language}
                    onChange={(e) => setLanguagePref(e.target.value as 'nb-NO' | 'en-US')}
                >
                    <option value="nb-NO">Norsk</option>
                    <option value="en-US">English</option>
                </select>
            </label>
            <div className="flex justify-end gap-2 mt-2">
                <button type="button" className="btn-secondary" onClick={onCancel}>
                    {t('common.cancel')}
                </button>
                <button type="submit" className="btn-primary">
                    {t('common.save')}
                </button>
            </div>
        </form>
    );
}
