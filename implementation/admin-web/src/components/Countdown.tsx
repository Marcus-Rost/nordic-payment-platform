// Canonical countdown component per gui/architecture/COMPONENTS_COUNTDOWN.md.
//
// Surfaces threshold-based aria-live announcements at 30s / 15s / 5s / 0s
// (WCAG 2.2.1 clause (c)). Used by:
//   - UC-G36 PII reveal (60-s TTL)
//   - UC-G52 session-expiry warning (5-min TTL)
//
// The component drives only the visual + aria announcements. The caller is
// responsible for the actual side-effect at expiry (re-mask, force logout).

import { useEffect, useRef, useState } from 'react';

export interface CountdownProps {
    expiresAt: number; // unix epoch seconds
    onExpired?: () => void;
    label?: (secondsRemaining: number) => string;
    className?: string;
}

const THRESHOLDS = [30, 15, 5, 0];

export function Countdown({ expiresAt, onExpired, label, className }: CountdownProps) {
    const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
    const announcedRef = useRef<Set<number>>(new Set());
    const expiredFiredRef = useRef(false);

    useEffect(() => {
        const id = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1_000);
        return () => clearInterval(id);
    }, []);

    const remaining = Math.max(0, expiresAt - now);

    useEffect(() => {
        if (remaining === 0 && !expiredFiredRef.current) {
            expiredFiredRef.current = true;
            onExpired?.();
        }
    }, [remaining, onExpired]);

    // Whether to push a live-region update this render.
    const announce = THRESHOLDS.find(
        (t) => remaining <= t && !announcedRef.current.has(t)
    );
    if (announce !== undefined) {
        announcedRef.current.add(announce);
    }

    return (
        <span className={className}>
            <span aria-hidden="true">{remaining}s</span>
            <span className="sr-only" role="status" aria-live="polite">
                {announce !== undefined && (label ? label(announce) : `${announce} seconds remaining`)}
            </span>
        </span>
    );
}
