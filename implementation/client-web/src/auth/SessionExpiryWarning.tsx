// UC-G52 — 5-minute warning before the customer's session expires.
// The bedrock countdown lives on AuthContext's tick; this component only
// observes and renders.

import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { beginLogin } from './oauth';

export function SessionExpiryWarning() {
    const { t } = useTranslation();
    const { session, expiresInSeconds, logout } = useAuth();
    if (!session) return null;
    if (expiresInSeconds <= 0) {
        // Session expired — force logout and redirect to login.
        logout();
        void beginLogin(window.location.pathname);
        return null;
    }
    if (expiresInSeconds > 5 * 60) return null;

    const minutes = Math.floor(expiresInSeconds / 60);
    const seconds = expiresInSeconds % 60;

    return (
        <div
            role="alert"
            className="fixed bottom-4 right-4 z-50 max-w-sm card border border-amber-300 p-4 bg-amber-50"
        >
            <p className="font-semibold text-amber-900">{t('session.expiringTitle')}</p>
            <p className="text-sm text-amber-800 mt-1">
                {t('session.expiringBody', {
                    minutes: String(minutes).padStart(2, '0'),
                    seconds: String(seconds).padStart(2, '0'),
                })}
            </p>
            <button
                className="btn-secondary mt-3"
                onClick={() => void beginLogin(window.location.pathname)}
            >
                {t('session.reauthenticate')}
            </button>
        </div>
    );
}
