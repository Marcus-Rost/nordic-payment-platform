import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function AuthGuard({ children }: { children: ReactNode }) {
    const { session } = useAuth();
    const location = useLocation();
    if (!session) {
        return <Navigate to="/login" replace state={{ returnTo: location.pathname }} />;
    }
    return <>{children}</>;
}

export function RoleGuard({
    children,
    allowedRoles,
    requiredScope,
}: {
    children: ReactNode;
    allowedRoles?: string[];
    requiredScope?: string;
}) {
    const { session } = useAuth();
    if (!session) return null;
    if (allowedRoles && !allowedRoles.includes(session.payload.role ?? '')) {
        return <p className="text-jrpay-red">Forbidden — your role lacks access.</p>;
    }
    if (requiredScope) {
        const scopes = (session.payload.scope ?? '').split(/\s+/);
        if (!scopes.includes(requiredScope)) {
            return <p className="text-jrpay-red">Forbidden — missing scope {requiredScope}.</p>;
        }
    }
    return <>{children}</>;
}
