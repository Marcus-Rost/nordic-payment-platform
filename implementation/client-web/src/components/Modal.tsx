import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';

/**
 * Accessible modal primitive (WCAG 2.4.3 + 2.4.7 + 3.3.x):
 *   - role=dialog + aria-modal=true
 *   - focus trapped while open (Tab/Shift+Tab cycle through focusable nodes)
 *   - ESC closes
 *   - focus returns to the previously-focused element on close
 */
export function Modal({
    titleId,
    onClose,
    children,
    widthClass = 'max-w-lg',
}: {
    titleId: string;
    onClose: () => void;
    children: ReactNode;
    widthClass?: string;
}) {
    const containerRef = useRef<HTMLDivElement>(null);
    const previouslyFocused = useRef<HTMLElement | null>(null);

    useEffect(() => {
        previouslyFocused.current = document.activeElement as HTMLElement | null;
        const container = containerRef.current;
        if (!container) return;
        const focusables = () =>
            container.querySelectorAll<HTMLElement>(
                'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
            );
        focusables()[0]?.focus();
        function onKey(e: KeyboardEvent) {
            if (e.key === 'Escape') {
                e.preventDefault();
                onClose();
                return;
            }
            if (e.key !== 'Tab') return;
            const f = focusables();
            if (f.length === 0) return;
            const first = f[0]!;
            const last = f[f.length - 1]!;
            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
        document.addEventListener('keydown', onKey);
        return () => {
            document.removeEventListener('keydown', onKey);
            previouslyFocused.current?.focus();
        };
    }, [onClose]);

    return (
        <div
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4"
            onMouseDown={(e) => {
                if (e.target === e.currentTarget) onClose();
            }}
        >
            <div
                ref={containerRef}
                className={`bg-white rounded-lg shadow-xl w-full p-6 ${widthClass}`}
                onMouseDown={(e) => e.stopPropagation()}
            >
                {children}
            </div>
        </div>
    );
}
