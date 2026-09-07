import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { beginLogin } from './oauth';

export function LoginPage() {
    const { t } = useTranslation();
    const location = useLocation() as { state?: { returnTo?: string } };

    return (
        <main className="min-h-screen grid place-items-center bg-slate-50 px-4">
            <section className="card max-w-md w-full p-8" aria-labelledby="login-title">
                <h1 id="login-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('login.title')}
                </h1>
                <p className="mt-2 text-slate-600">{t('login.subtitle')}</p>
                <button
                    className="btn-primary mt-6 w-full"
                    onClick={() => void beginLogin(location.state?.returnTo ?? '/')}
                >
                    {t('login.continueWithBankId')}
                </button>
                <p className="mt-4 text-xs text-slate-500">{t('login.scaNotice')}</p>
            </section>
        </main>
    );
}
