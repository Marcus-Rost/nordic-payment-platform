import { describe, expect, it } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { ToastProvider, useToasts } from '../Toast';
import { ProblemError } from '../../api/problem';

function Trigger({
    onReady,
}: {
    onReady: (t: ReturnType<typeof useToasts>) => void;
}) {
    const t = useToasts();
    onReady(t);
    return null;
}

describe('Toast / useToasts', () => {
    it('renders ProblemError with title, detail, and type URN', () => {
        let api!: ReturnType<typeof useToasts>;
        render(
            <ToastProvider>
                <Trigger onReady={(t) => (api = t)} />
            </ToastProvider>
        );
        act(() => {
            api.showProblem(
                new ProblemError(
                    {
                        type: 'urn:problem:case-already-decided',
                        title: 'Already decided',
                        status: 409,
                        detail: 'Decided 2 minutes ago.',
                    },
                    new Response()
                )
            );
        });

        expect(screen.getByText('Already decided')).toBeInTheDocument();
        expect(screen.getByText('Decided 2 minutes ago.')).toBeInTheDocument();
        expect(
            screen.getByText('urn:problem:case-already-decided')
        ).toBeInTheDocument();
    });

    it('falls back gracefully on plain Error', () => {
        let api!: ReturnType<typeof useToasts>;
        render(
            <ToastProvider>
                <Trigger onReady={(t) => (api = t)} />
            </ToastProvider>
        );
        act(() => api.showProblem(new Error('boom')));
        expect(screen.getByText('Unexpected error')).toBeInTheDocument();
        expect(screen.getByText('boom')).toBeInTheDocument();
    });
});
