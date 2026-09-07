import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { setBearerProvider } from '../api/client';
import { decodeJwt, expiresInSeconds, isExpired, JwtPayload } from './jwt';

const STORAGE_KEY = 'jrpay.client.session';

export interface CustomerSession {
    accessToken: string;
    refreshToken?: string;
    issuedAt: number;
    expiresAt: number;
    payload: JwtPayload;
}

interface AuthContextValue {
    session: CustomerSession | null;
    setSession: (session: CustomerSession | null) => void;
    logout: () => void;
    customerId: string | null;
    expiresInSeconds: number;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function load(): CustomerSession | null {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
        const parsed = JSON.parse(raw) as CustomerSession;
        if (isExpired(parsed.payload)) return null;
        return parsed;
    } catch {
        return null;
    }
}

function save(session: CustomerSession | null): void {
    if (!session) {
        sessionStorage.removeItem(STORAGE_KEY);
    } else {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    }
}

export function AuthProvider({ children }: { children: ReactNode }) {
    const [session, setSessionState] = useState<CustomerSession | null>(load);

    const setSession = useCallback((next: CustomerSession | null) => {
        save(next);
        setSessionState(next);
    }, []);

    const logout = useCallback(() => setSession(null), [setSession]);

    useEffect(() => {
        setBearerProvider(() =>
            session && !isExpired(session.payload) ? session.accessToken : null
        );
    }, [session]);

    // Tick once a second so countdown components reflect expiry without
    // remounting.
    const [, setTick] = useState(0);
    useEffect(() => {
        const id = setInterval(() => setTick((t) => t + 1), 1_000);
        return () => clearInterval(id);
    }, []);

    const value = useMemo<AuthContextValue>(
        () => ({
            session,
            setSession,
            logout,
            customerId: session?.payload.jrpay_uid ?? session?.payload.sub ?? null,
            expiresInSeconds: expiresInSeconds(session?.payload ?? null),
        }),
        [session, setSession, logout]
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
    return ctx;
}

export function buildSession(
    accessToken: string,
    refreshToken?: string
): CustomerSession | null {
    const payload = decodeJwt(accessToken);
    if (!payload) return null;
    return {
        accessToken,
        refreshToken,
        issuedAt: Math.floor(Date.now() / 1000),
        expiresAt: payload.exp ?? Math.floor(Date.now() / 1000) + 900,
        payload,
    };
}
