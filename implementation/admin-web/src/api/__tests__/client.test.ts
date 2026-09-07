import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, newCorrelationId, newIdempotencyKey, ProblemError, setBearerProvider } from '../client';

describe('api wrapper', () => {
    let originalFetch: typeof globalThis.fetch;

    beforeEach(() => {
        originalFetch = globalThis.fetch;
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
        setBearerProvider(() => null);
    });

    it('injects the bearer token from the configured provider', async () => {
        setBearerProvider(() => 'opaque-token');
        const calls: { url: string; init: RequestInit }[] = [];
        globalThis.fetch = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
            calls.push({ url: String(url), init: init ?? {} });
            return new Response(JSON.stringify({ ok: true }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        }) as unknown as typeof fetch;

        await api<{ ok: boolean }>('/api/v1/admin/fraud/cases');
        const headers = new Headers(calls[0]!.init.headers);
        expect(headers.get('Authorization')).toBe('Bearer opaque-token');
    });

    it('auto-generates an Idempotency-Key on POST', async () => {
        const seen: string[] = [];
        globalThis.fetch = vi.fn(async (_url, init?: RequestInit) => {
            const headers = new Headers(init?.headers);
            const key = headers.get('Idempotency-Key');
            if (key) seen.push(key);
            return new Response(null, { status: 204 });
        }) as unknown as typeof fetch;

        await api('/x', { method: 'POST', body: { a: 1 } });
        await api('/x', { method: 'POST', body: { a: 2 } });
        expect(seen).toHaveLength(2);
        expect(seen[0]).not.toBe(seen[1]);
    });

    it('forwards Step-Up-Token when provided', async () => {
        let captured: Headers | null = null;
        globalThis.fetch = vi.fn(async (_url, init?: RequestInit) => {
            captured = new Headers(init?.headers);
            return new Response(null, { status: 204 });
        }) as unknown as typeof fetch;

        await api('/y', { method: 'POST', stepUpToken: 'sigil', body: {} });
        expect(captured!.get('Step-Up-Token')).toBe('sigil');
    });

    it('serialises arrays in query parameters', async () => {
        let url: string = '';
        globalThis.fetch = vi.fn(async (u) => {
            url = String(u);
            return new Response(JSON.stringify({ items: [] }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        }) as unknown as typeof fetch;

        await api('/api/v1/admin/fraud/cases', {
            query: { status: ['OPEN', 'ASSIGNED'], minScore: 0.5 },
        });
        expect(url).toContain('status=OPEN');
        expect(url).toContain('status=ASSIGNED');
        expect(url).toContain('minScore=0.5');
    });

    it('throws ProblemError on 4xx responses', async () => {
        globalThis.fetch = vi.fn(async () =>
            new Response(
                JSON.stringify({
                    type: 'urn:problem:step-up-required',
                    title: 'Step-up SCA required',
                    status: 401,
                }),
                { status: 401, headers: { 'Content-Type': 'application/problem+json' } }
            )
        ) as unknown as typeof fetch;

        await expect(api('/z')).rejects.toBeInstanceOf(ProblemError);
    });

    it('returns text when content-type is not JSON', async () => {
        globalThis.fetch = vi.fn(async () =>
            new Response('hello', {
                status: 200,
                headers: { 'Content-Type': 'text/plain' },
            })
        ) as unknown as typeof fetch;
        const v = await api<string>('/t');
        expect(v).toBe('hello');
    });
});

describe('id helpers', () => {
    it('newCorrelationId / newIdempotencyKey produce UUIDs', () => {
        const a = newCorrelationId();
        const b = newIdempotencyKey();
        expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        expect(b).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        expect(a).not.toBe(b);
    });
});
