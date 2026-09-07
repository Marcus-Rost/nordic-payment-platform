import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { buildSession, useAuth } from './AuthContext';
import { completeLogin, readReturnTo } from './oauth';

export function CallbackPage() {
    const { t } = useTranslation();
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const { setSession } = useAuth();
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const code = params.get('code');
        const state = params.get('state');
        const errorParam = params.get('error');
        const returnTo = readReturnTo();

        if (errorParam) {
            setError(`${errorParam}: ${params.get('error_description') ?? ''}`);
            return;
        }
        if (!code || !state) {
            setError('Missing code or state');
            return;
        }
        completeLogin(code, state)
            .then((tokens) => {
                const session = buildSession(tokens.access_token, tokens.refresh_token);
                if (!session) {
                    setError('Could not decode access token');
                    return;
                }
                setSession(session);
                navigate(returnTo, { replace: true });
            })
            .catch((e) => setError(e instanceof Error ? e.message : String(e)));
    }, [params, navigate, setSession]);

    return (
        <main className="min-h-screen grid place-items-center bg-slate-50 px-4">
            <section className="card max-w-md w-full p-8" aria-live="polite">
                {error ? (
                    <>
                        <h1 className="text-xl font-semibold text-jrpay-red">
                            {t('callback.failed')}
                        </h1>
                        <p className="mt-2 text-sm text-slate-600">{error}</p>
                        <button
                            className="btn-secondary mt-4"
                            onClick={() => navigate('/login', { replace: true })}
                        >
                            {t('callback.tryAgain')}
                        </button>
                    </>
                ) : (
                    <>
                        <h1 className="text-xl font-semibold text-jrpay-navy">
                            {t('callback.exchanging')}
                        </h1>
                        <p className="mt-2 text-sm text-slate-600">
                            {t('callback.pleaseWait')}
                        </p>
                    </>
                )}
            </section>
        </main>
    );
}
