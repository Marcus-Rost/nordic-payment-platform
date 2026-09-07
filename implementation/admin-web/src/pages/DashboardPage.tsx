import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const TILES = [
    { to: '/fraud', titleKey: 'nav.fraud', scope: 'fraud:read' },
    { to: '/sanctions', titleKey: 'nav.sanctions', scope: 'sanctions:read' },
    { to: '/accounts', titleKey: 'nav.accounts', scope: 'accounts:admin:read' },
    { to: '/audit', titleKey: 'nav.audit', scope: 'audit:read' },
    { to: '/system', titleKey: 'nav.system', scope: 'system:read' },
    { to: '/users', titleKey: 'nav.users', scope: 'users:read' },
];

export function DashboardPage() {
    const { t } = useTranslation();
    const { session } = useAuth();
    const scopes = (session?.payload.scope ?? '').split(/\s+/);
    const visible = TILES.filter((tile) => !tile.scope || scopes.includes(tile.scope));

    return (
        <section>
            <h1 className="text-2xl font-semibold text-jrpay-navy mb-4">
                {t('app.title')}
            </h1>
            <p className="text-slate-600 mb-6">{t('app.subtitle')}</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {visible.map((tile) => (
                    <Link
                        key={tile.to}
                        to={tile.to}
                        className="bg-white rounded-lg shadow-sm hover:shadow-md transition-shadow p-5 border border-transparent hover:border-jrpay-navy"
                    >
                        <h2 className="text-lg font-semibold text-jrpay-navy">
                            {t(tile.titleKey)}
                        </h2>
                    </Link>
                ))}
            </div>
        </section>
    );
}
