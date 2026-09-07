import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    api,
    newCorrelationId,
    newIdempotencyKey,
    ProblemError,
    setBearerProvider,
} from '../client';

describe('customer api wrapper', () => {
    let originalFetch: typeof globalThis.fetch;

    beforeEach(() => {
        originalFetch = globalThis.fetch;
    });
    afterEach(() => {
        globalThis.fetch = originalFetch;
        setBearerProvider(() => null);
    });

    it('adds bearer + Idempotency-Key + Step-Up-Token + traceparent', async () => {
        setBearerProvider(() => 'tok');
        let captured: Headers | null = null;
        globalThis.fetch = vi.fn(async (_url, init?: RequestInit) => {
            captured = new Headers(init?.headers);
            return new Response(null, { status: 204 });
        }) as unknown as typeof fetch;

        await api('/api/v1/customer/payments', {
            method: 'POST',
            stepUpToken: 'sigil',
            body: { amount: '1.00' },
        });
        expect(captured!.get('Authorization')).toBe('Bearer tok');
        expect(captured!.get('Idempotency-Key')).toMatch(/[0-9a-f-]{36}/);
        expect(captured!.get('Step-Up-Token')).toBe('sigil');
        expect(captured!.get('traceparent')).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    });

    it('throws ProblemError on non-ok responses', async () => {
        globalThis.fetch = vi.fn(async () =>
            new Response(
                JSON.stringify({
                    type: 'urn:problem:vop-mismatch',
                    title: 'Payee mismatch',
                    status: 422,
                }),
                { status: 422, headers: { 'Content-Type': 'application/problem+json' } }
            )
        ) as unknown as typeof fetch;
        await expect(
            api('/api/v1/customer/payments/verify-payee', { method: 'POST', body: {} })
        ).rejects.toBeInstanceOf(ProblemError);
    });

    it('serialises array query params (e.g. status[])', async () => {
        let url = '';
        globalThis.fetch = vi.fn(async (u) => {
            url = String(u);
            return new Response(JSON.stringify({ items: [] }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        }) as unknown as typeof fetch;
        await api('/api/v1/customer/r2p', {
            query: { status: ['PENDING', 'AMENDMENT_REQUESTED'] },
        });
        expect(url).toContain('status=PENDING');
        expect(url).toContain('status=AMENDMENT_REQUESTED');
    });
});

describe('id helpers', () => {
    it('generate UUIDs', () => {
        expect(newCorrelationId()).toMatch(/^[0-9a-f-]{36}$/);
        expect(newIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/);
    });
});
