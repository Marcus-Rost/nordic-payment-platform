// Centralised RFC 9457 problem renderer for the customer surface. Auto-
// dismisses after 8 s; keyboard-accessible dismiss button.

import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ProblemError } from '../api/problem';

interface ToastEntry {
    id: number;
    severity: 'info' | 'warn' | 'error' | 'success';
    title: string;
    detail?: string;
    type?: string;
}

interface ToastContextValue {
    showProblem: (e: unknown) => void;
    showInfo: (title: string, detail?: string) => void;
    showSuccess: (title: string, detail?: string) => void;
    showError: (title: string, detail?: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
    const [entries, setEntries] = useState<ToastEntry[]>([]);
    const idRef = useRef(0);

    const dismiss = useCallback((id: number) => {
        setEntries((prev) => prev.filter((e) => e.id !== id));
    }, []);

    const push = useCallback(
        (entry: Omit<ToastEntry, 'id'>) => {
            idRef.current += 1;
            const id = idRef.current;
            setEntries((prev) => [...prev, { ...entry, id }]);
            setTimeout(() => dismiss(id), 8_000);
        },
        [dismiss]
    );

    const value = useMemo<ToastContextValue>(
        () => ({
            showProblem(e: unknown) {
                if (e instanceof ProblemError) {
                    push({
                        severity: 'error',
                        title: e.problem.title,
                        detail: e.problem.detail,
                        type: e.problem.type,
                    });
                } else {
                    push({
                        severity: 'error',
                        title: 'Unexpected error',
                        detail: e instanceof Error ? e.message : String(e),
                    });
                }
            },
            showInfo(title, detail) {
                push({ severity: 'info', title, detail });
            },
            showSuccess(title, detail) {
                push({ severity: 'success', title, detail });
            },
            showError(title, detail) {
                push({ severity: 'error', title, detail });
            },
        }),
        [push]
    );

    return (
        <ToastContext.Provider value={value}>
            {children}
            <div
                aria-live="polite"
                className="fixed top-4 right-4 z-[100] flex flex-col gap-2 max-w-md"
            >
                {entries.map((e) => (
                    <div
                        key={e.id}
                        role={e.severity === 'error' ? 'alert' : 'status'}
                        className={`rounded-lg shadow-lg px-4 py-3 ${
                            e.severity === 'error'
                                ? 'bg-jrpay-red text-white'
                                : e.severity === 'warn'
                                  ? 'bg-jrpay-amber text-slate-900'
                                  : e.severity === 'success'
                                    ? 'bg-jrpay-green text-white'
                                    : 'bg-white text-slate-900 border border-slate-200'
                        }`}
                    >
                        <div className="flex items-start justify-between gap-2">
                            <div>
                                <p className="font-medium">{e.title}</p>
                                {e.detail && <p className="text-sm mt-1">{e.detail}</p>}
                                {e.type && (
                                    <p className="text-xs mt-1 opacity-75 font-mono">{e.type}</p>
                                )}
                            </div>
                            <button
                                onClick={() => dismiss(e.id)}
                                className="text-current opacity-75 hover:opacity-100"
                                aria-label="Dismiss"
                            >
                                ×
                            </button>
                        </div>
                    </div>
                ))}
            </div>
        </ToastContext.Provider>
    );
}

export function useToasts(): ToastContextValue {
    const ctx = useContext(ToastContext);
    if (!ctx) throw new Error('useToasts must be used inside <ToastProvider>');
    return ctx;
}
