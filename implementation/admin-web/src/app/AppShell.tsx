import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { setLanguage } from '../i18n/i18n';
import { revokeRefreshToken } from '../auth/oauth';
import { StatusBanner } from '../components/StatusBanner';
import { SessionExpiryWarning } from '../auth/SessionExpiryWarning';

const NAV_ITEMS: { to: string; key: string; scope?: string }[] = [
    { to: '/', key: 'nav.dashboard' },
    { to: '/fraud', key: 'nav.fraud', scope: 'fraud:read' },
    { to: '/sanctions', key: 'nav.sanctions', scope: 'sanctions:read' },
    { to: '/accounts', key: 'nav.accounts', scope: 'accounts:admin:read' },
    { to: '/audit', key: 'nav.audit', scope: 'audit:read' },
    { to: '/system', key: 'nav.system', scope: 'system:read' },
    { to: '/users', key: 'nav.users', scope: 'users:read' },
];

export function AppShell() {
    const { t, i18n } = useTranslation();
    const { session, logout } = useAuth();

    function onLogout() {
        if (session?.refreshToken) {
            void revokeRefreshToken(session.refreshToken);
        }
        logout();
    }

    return (
        <div className="min-h-screen flex flex-col bg-slate-100">
            <StatusBanner banner={null} />
            <header className="bg-jrpay-navy text-white">
                <div className="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
                    <div>
                        <h1 className="text-lg font-semibold">{t('app.title')}</h1>
                        <p className="text-xs opacity-75">{t('app.subtitle')}</p>
                    </div>
                    <div className="flex items-center gap-3 text-sm">
                        <label className="sr-only" htmlFor="lang-toggle">
                            {t('common.language')}
                        </label>
                        <select
                            id="lang-toggle"
                            value={i18n.language}
                            onChange={(e) =>
                                setLanguage(e.target.value as 'nb-NO' | 'en-US')
                            }
                            className="bg-jrpay-navy-soft text-white border border-white/30 rounded px-2 py-1"
                        >
                            <option value="nb-NO">Norsk</option>
                            <option value="en-US">English</option>
                        </select>
                        {session && (
                            <>
                                <span aria-label="operator">
                                    {session.payload.sub} ·{' '}
                                    <span className="opacity-75">
                                        {session.payload.role ?? 'OPERATOR'}
                                    </span>
                                </span>
                                <button
                                    className="bg-white/10 hover:bg-white/20 px-3 py-1 rounded"
                                    onClick={onLogout}
                                >
                                    {t('common.logout')}
                                </button>
                            </>
                        )}
                    </div>
                </div>
                <nav aria-label="primary" className="max-w-7xl mx-auto px-4 pb-2">
                    <ul className="flex gap-1 overflow-x-auto">
                        {NAV_ITEMS.map((item) => (
                            <li key={item.to}>
                                <NavLink
                                    to={item.to}
                                    end={item.to === '/'}
                                    className={({ isActive }) =>
                                        `px-3 py-1.5 rounded-md text-sm transition-colors ${
                                            isActive
                                                ? 'bg-white text-jrpay-navy'
                                                : 'text-white/85 hover:bg-white/10'
                                        }`
                                    }
                                >
                                    {t(item.key)}
                                </NavLink>
                            </li>
                        ))}
                    </ul>
                </nav>
            </header>

            <main className="flex-1">
                <div className="max-w-7xl mx-auto px-4 py-6">
                    <Outlet />
                </div>
            </main>

            <SessionExpiryWarning />
        </div>
    );
}
