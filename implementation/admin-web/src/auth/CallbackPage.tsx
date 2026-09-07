import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { completeLogin } from './oauth';
import { readVerifier, clearVerifier } from './pkce';
import { buildSession, useAuth } from './AuthContext';

type CallbackState = 'pending' | 'error';

export function CallbackPage() {
    const { t } = useTranslation();
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const { setSession } = useAuth();
    const [state, setState] = useState<CallbackState>('pending');
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const code = params.get('code');
        const returnedState = params.get('state');
        const errorParam = params.get('error');

        if (errorParam) {
            setError(`${errorParam}: ${params.get('error_description') ?? ''}`);
            setState('error');
            clearVerifier();
            return;
        }
        if (!code || !returnedState) {
            setError('Missing code or state in callback');
            setState('error');
            return;
        }

        const stash = readVerifier();
        if (!stash) {
            setError('Missing PKCE verifier in session storage');
            setState('error');
            return;
        }

        completeLogin(code, returnedState, stash.state, stash.verifier)
            .then((tokens) => {
                const session = buildSession(tokens.access_token, tokens.refresh_token);
                if (!session) {
                    setError('Returned access token could not be decoded');
                    setState('error');
                    return;
                }
                setSession(session);
                const returnTo = sessionStorage.getItem('jrpay.admin.returnTo') ?? '/';
                sessionStorage.removeItem('jrpay.admin.returnTo');
                navigate(returnTo, { replace: true });
            })
            .catch((e: unknown) => {
                setError(e instanceof Error ? e.message : String(e));
                setState('error');
            });
    }, [params, navigate, setSession]);

    return (
        <main className="min-h-screen flex items-center justify-center px-6">
            <div className="max-w-md w-full bg-white rounded-lg shadow p-8 text-center">
                {state === 'pending' && (
                    <>
                        <h1 className="text-xl font-semibold text-jrpay-navy">
                            {t('callback.exchanging')}
                        </h1>
                        <p className="text-sm text-slate-600 mt-2">{t('callback.pleaseWait')}</p>
                    </>
                )}
                {state === 'error' && (
                    <>
                        <h1 className="text-xl font-semibold text-jrpay-red">
                            {t('callback.failed')}
                        </h1>
                        <p className="text-sm text-slate-700 mt-2 break-all">{error}</p>
                        <button
                            className="btn-secondary mt-6"
                            onClick={() => navigate('/login', { replace: true })}
                        >
                            {t('callback.tryAgain')}
                        </button>
                    </>
                )}
            </div>
        </main>
    );
}
