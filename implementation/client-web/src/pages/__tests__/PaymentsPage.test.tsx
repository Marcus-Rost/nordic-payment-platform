// Integration test for PaymentsPage:
//   - mocks /accounts + /verify-payee + /scheduled
//   - renders the form, fills it, asserts the VoP badge updates

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { PaymentsPage } from '../payments/PaymentsPage';
import { ToastProvider } from '../../components/Toast';
import { AuthProvider } from '../../auth/AuthContext';
import '../../i18n/i18n';

function mockFetch(routes: { match: RegExp; respond: (init?: RequestInit) => Response }[]) {
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        for (const r of routes) {
            if (r.match.test(url)) return r.respond(init);
        }
        return new Response(null, { status: 404 });
    }) as unknown as typeof fetch;
}

function renderPage() {
    return render(
        <MemoryRouter>
            <AuthProvider>
                <ToastProvider>
                    <PaymentsPage />
                </ToastProvider>
            </AuthProvider>
        </MemoryRouter>
    );
}

const accountsResp = {
    items: [
        {
            accountId: 'acc-1',
            iban: 'NO9300000000000',
            type: 'CHECKING',
            currency: 'NOK',
            availableBalance: '5000.00',
            status: 'OPEN',
            nickname: 'Brukskonto',
        },
    ],
};

describe('PaymentsPage', () => {
    let originalFetch: typeof globalThis.fetch;
    beforeEach(() => {
        originalFetch = globalThis.fetch;
        sessionStorage.clear();
    });
    afterEach(() => {
        globalThis.fetch = originalFetch;
    });

    it('renders the form with the customer account preselected', async () => {
        mockFetch([
            {
                match: /\/api\/v1\/customer\/accounts$/,
                respond: () =>
                    new Response(JSON.stringify(accountsResp), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    }),
            },
            {
                match: /\/api\/v1\/customer\/payments\/scheduled/,
                respond: () =>
                    new Response(JSON.stringify({ items: [] }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    }),
            },
        ]);
        renderPage();
        await waitFor(() => {
            expect(screen.getByDisplayValue(/Brukskonto/)).toBeInTheDocument();
        });
    });

    it('runs VoP and surfaces a MATCH badge', async () => {
        mockFetch([
            {
                match: /\/api\/v1\/customer\/accounts$/,
                respond: () =>
                    new Response(JSON.stringify(accountsResp), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    }),
            },
            {
                match: /\/api\/v1\/customer\/payments\/scheduled/,
                respond: () =>
                    new Response(JSON.stringify({ items: [] }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    }),
            },
            {
                match: /\/api\/v1\/customer\/payments\/verify-payee/,
                respond: () =>
                    new Response(JSON.stringify({ result: 'MATCH' }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    }),
            },
        ]);
        renderPage();
        await waitFor(() => screen.getByDisplayValue(/Brukskonto/));

        const ibanInput = screen.getAllByLabelText(/IBAN/i)[0]!;
        const nameInput = screen.getAllByLabelText(/Mottakers navn|Creditor name/)[0]!;
        const amountInput = screen.getAllByLabelText(/Beløp|Amount/)[0]!;

        fireEvent.change(ibanInput, { target: { value: 'NO9311111111111' } });
        fireEvent.change(nameInput, { target: { value: 'Acme Corp' } });
        fireEvent.change(amountInput, { target: { value: '100.00' } });

        await waitFor(
            () => {
                expect(screen.getByText(/Mottaker bekreftet|Payee verified/i)).toBeInTheDocument();
            },
            { timeout: 2000 }
        );
    });
});
