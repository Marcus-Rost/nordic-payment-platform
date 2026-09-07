import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { setBearerProvider } from '../api/client';
import { decodeJwt, expiresInSeconds, isAdmin, isExpired, JwtPayload } from './jwt';

const STORAGE_KEY = 'jrpay.admin.session';

export interface AdminSession {
    accessToken: string;
    refreshToken?: string;
    issuedAt: number;
    expiresAt: number;
    payload: JwtPayload;
}

interface AuthContextValue {
    session: AdminSession | null;
    setSession: (session: AdminSession | null) => void;
    logout: () => void;
    operatorId: string | null;
    role: string | null;
    isAdmin: boolean;
    expiresInSeconds: number;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function load(): AdminSession | null {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
        const parsed = JSON.parse(raw) as AdminSession;
        if (isExpired(parsed.payload)) return null;
        return parsed;
    } catch {
        return null;
    }
}

function save(session: AdminSession | null): void {
    if (!session) {
        sessionStorage.removeItem(STORAGE_KEY);
    } else {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    }
}

export function AuthProvider({ children }: { children: ReactNode }) {
    const [session, setSessionState] = useState<AdminSession | null>(load);

    const setSession = useCallback((next: AdminSession | null) => {
        save(next);
        setSessionState(next);
    }, []);

    const logout = useCallback(() => {
        setSession(null);
    }, [setSession]);

    // Keep the api/client bearer-provider in lockstep with the session.
    useEffect(() => {
        setBearerProvider(() => (session && !isExpired(session.payload) ? session.accessToken : null));
    }, [session]);

    // Tick: re-render once a second so countdown components reflect ttl.
    const [, setTick] = useState(0);
    useEffect(() => {
        const id = setInterval(() => setTick((t) => t + 1), 1_000);
        return () => clearInterval(id);
    }, []);

    const value = useMemo<AuthContextValue>(() => {
        return {
            session,
            setSession,
            logout,
            operatorId: session?.payload.jrpay_uid ?? null,
            role: session?.payload.role ?? null,
            isAdmin: isAdmin(session?.payload ?? null),
            expiresInSeconds: expiresInSeconds(session?.payload ?? null),
        };
    }, [session, setSession, logout]);

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
    return ctx;
}

export function buildSession(accessToken: string, refreshToken?: string): AdminSession | null {
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
