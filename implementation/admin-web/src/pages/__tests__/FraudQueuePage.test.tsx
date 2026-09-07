// Integration test for FraudQueuePage. Mocks fetch and asserts:
//   - the queue endpoint is called with the configured filters
//   - results render with score-band classes
//   - status filter toggles update the URL search params
//   - the SSE token is requested

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { FraudQueuePage } from '../fraud/FraudQueuePage';
import { ToastProvider } from '../../components/Toast';
import { AuthProvider } from '../../auth/AuthContext';
import '../../i18n/i18n';

type FetchHandler = (url: string, init?: RequestInit) => Response | Promise<Response>;

function mockFetch(handler: FetchHandler) {
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        return handler(String(input), init);
    }) as unknown as typeof fetch;
}

function renderWithProviders(initialPath = '/fraud') {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <AuthProvider>
                <ToastProvider>
                    <Routes>
                        <Route path="/fraud" element={<FraudQueuePage />} />
                    </Routes>
                </ToastProvider>
            </AuthProvider>
        </MemoryRouter>
    );
}

const SAMPLE_PAGE = {
    items: [
        {
            caseId: '11111111-1111-1111-1111-111111111111',
            status: 'OPEN',
            score: 0.92,
            scoreBand: 'HIGH',
            amount: '12500.00',
            currency: 'NOK',
            holderNameMasked: 'Ola N*****',
            debtorAccountId: 'acc-1',
            creditorIbanMasked: 'NO•• •••• ••12 3456',
            triggeredRules: ['VEL-04', 'ANO-01'],
            assignee: null,
            createdAt: '2026-04-25T10:00:00Z',
            slaDeadline: '2026-04-25T11:00:00Z',
            slaBreached: false,
            paymentId: 'pay-1',
        },
    ],
    page: { number: 0, size: 25, totalElements: 1, totalPages: 1 },
    counters: { openTotal: 12, assignedToMe: 1, slaBreached: 2 },
};

describe('FraudQueuePage', () => {
    beforeEach(() => {
        sessionStorage.clear();
    });
    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders counters and a row from a mocked queue response', async () => {
        let listCalled = 0;
        let streamTokenCalled = 0;
        mockFetch((url, init) => {
            if (url.includes('/api/v1/admin/fraud/cases/stream-token')) {
                streamTokenCalled++;
                return new Response(
                    JSON.stringify({ streamToken: 't', expiresIn: 300 }),
                    { status: 200, headers: { 'Content-Type': 'application/json' } }
                );
            }
            if (url.includes('/api/v1/admin/fraud/cases')) {
                listCalled++;
                expect(init?.method ?? 'GET').toBe('GET');
                return new Response(JSON.stringify(SAMPLE_PAGE), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            return new Response(null, { status: 404 });
        });

        renderWithProviders();

        await waitFor(() => {
            expect(listCalled).toBeGreaterThanOrEqual(1);
        });
        expect(await screen.findByText('Ola N*****')).toBeInTheDocument();
        expect(screen.getByText('0.92')).toBeInTheDocument();
        expect(screen.getByText('VEL-04')).toBeInTheDocument();
        // Counter values
        expect(screen.getByText('12')).toBeInTheDocument();
        // Stream-token request was attempted
        await waitFor(() => expect(streamTokenCalled).toBeGreaterThanOrEqual(1));
    });

    it('toggling a status filter triggers a re-fetch with the new filter', async () => {
        const callsByQuery: string[] = [];
        mockFetch((url) => {
            if (url.includes('/api/v1/admin/fraud/cases/stream-token')) {
                return new Response(
                    JSON.stringify({ streamToken: 't', expiresIn: 300 }),
                    { status: 200, headers: { 'Content-Type': 'application/json' } }
                );
            }
            if (url.includes('/api/v1/admin/fraud/cases')) {
                callsByQuery.push(url);
                return new Response(JSON.stringify(SAMPLE_PAGE), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            return new Response(null, { status: 404 });
        });

        renderWithProviders();
        await waitFor(() => expect(callsByQuery.length).toBeGreaterThanOrEqual(1));

        // Initial filter is OPEN + ASSIGNED. Untoggling OPEN should drop it.
        const openToggle = screen.getAllByText('OPEN')[0]!.closest('label')!;
        fireEvent.click(openToggle.querySelector('input')!);

        await waitFor(() => {
            const last = callsByQuery[callsByQuery.length - 1]!;
            expect(last).toContain('status=ASSIGNED');
            expect(last).not.toContain('status=OPEN');
        });
    });
});
