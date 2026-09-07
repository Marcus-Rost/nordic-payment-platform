// UC-G52 — warn the operator 5 minutes before the access token expires.
// They can re-authenticate (full re-login) or wait until expiry forces it.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { beginLogin } from './oauth';

const WARN_AT_SECONDS = 5 * 60;

export function SessionExpiryWarning() {
    const { session, expiresInSeconds, logout } = useAuth();
    const { t } = useTranslation();
    const [dismissed, setDismissed] = useState(false);

    useEffect(() => {
        // Reset dismissed state when a new session is established.
        setDismissed(false);
    }, [session?.issuedAt]);

    if (!session) return null;
    if (expiresInSeconds > WARN_AT_SECONDS) return null;
    if (dismissed) return null;

    if (expiresInSeconds <= 0) {
        // Force logout on expiry.
        logout();
        return null;
    }

    const minutes = Math.floor(expiresInSeconds / 60);
    const seconds = expiresInSeconds % 60;

    return (
        <div
            role="alert"
            aria-live="polite"
            className="fixed bottom-6 right-6 max-w-sm bg-jrpay-amber text-slate-900 rounded-lg shadow-lg p-4 z-50"
        >
            <h2 className="font-semibold mb-1">{t('session.expiringTitle')}</h2>
            <p className="text-sm">
                {t('session.expiringBody', {
                    minutes: String(minutes).padStart(2, '0'),
                    seconds: String(seconds).padStart(2, '0'),
                })}
            </p>
            <div className="mt-3 flex gap-2">
                <button className="btn-primary" onClick={() => beginLogin(window.location.pathname)}>
                    {t('session.reauthenticate')}
                </button>
                <button className="btn-secondary" onClick={() => setDismissed(true)}>
                    {t('session.dismiss')}
                </button>
            </div>
        </div>
    );
}
