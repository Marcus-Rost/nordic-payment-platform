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
