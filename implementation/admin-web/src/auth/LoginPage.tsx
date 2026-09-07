import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { beginLogin } from './oauth';

export function LoginPage() {
    const { t } = useTranslation();
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    async function onLogin() {
        try {
            setLoading(true);
            await beginLogin('/');
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
            setLoading(false);
        }
    }

    return (
        <main className="min-h-screen flex items-center justify-center px-6">
            <div className="max-w-md w-full bg-white rounded-lg shadow p-8">
                <h1 className="text-2xl font-semibold text-jrpay-navy mb-2">
                    {t('login.title')}
                </h1>
                <p className="text-slate-600 mb-6">{t('login.subtitle')}</p>
                <button
                    type="button"
                    className="btn-primary w-full"
                    onClick={onLogin}
                    disabled={loading}
                >
                    {loading ? t('login.redirecting') : t('login.continueWithBankId')}
                </button>
                {error && (
                    <p className="mt-4 text-sm text-jrpay-red" role="alert">
                        {error}
                    </p>
                )}
                <p className="mt-6 text-xs text-slate-500">
                    {t('login.scaNotice')}
                </p>
            </div>
        </main>
    );
}
