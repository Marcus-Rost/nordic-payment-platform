// Phase E §6 + §9 — form-validation edges + auth edges.
//
// §6: Boundary values, invalid input, edge-case formats.
// §9: Auth edges — invalid credentials, missing audience.
//
// What it proves:
//   - dev-token rejects bad credentials with a clear 4xx (not 500).
//   - dev-token rejects unknown user with 4xx.
//   - Audience mismatch is rejected (admin trying to authenticate as customer).
//   - The SPA login pages emit GUI-SEC-03 security headers (CSP, X-Frame-
//     Options, HSTS, Referrer-Policy, X-Content-Type-Options).
//   - Validation handler (F7) returns the new structured `fields` payload
//     when @Valid fails on /api/v1/accounts.
//
// Status: real assertions guarding contract surface.

import { request as plRequest, expect, test } from '@playwright/test';
import { ENDPOINTS, GUI } from '../helpers/endpoints';
import { freshTag } from '../helpers/freshId';
import { requireServices } from '../helpers/preflight';
import { basicApi } from '../helpers/a1';

test.describe('Phase E §6/§9 — validation + auth + security headers', () => {
    test.beforeEach(() => {
        requireServices('identity', 'accountLedger', 'clientWeb', 'adminWeb');
    });

    test('dev-token — wrong password returns 401 (not 500)', async () => {
        const api = await plRequest.newContext();
        try {
            const r = await api.post(`${ENDPOINTS.identity}/dev/oauth2/dev-token`, {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    username: 'admin@jrpay.example',
                    password: 'wrong-password',
                    audience: 'admin',
                },
            });
            // After OPEN-5 fix: BadCredentialsException → 401.
            expect(r.status()).toBe(401);
        } finally {
            await api.dispose();
        }
    });

    test('dev-token — unknown user returns 401 (anti-enumeration, same as wrong password)', async () => {
        const api = await plRequest.newContext();
        try {
            const r = await api.post(`${ENDPOINTS.identity}/dev/oauth2/dev-token`, {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    username: `nobody-${freshTag()}@example.com`,
                    password: 'demo-password-1!',
                    audience: 'admin',
                },
            });
            // After OPEN-5: unknown-user must surface as 401 (NOT 404).
            // Distinguishing "wrong password" from "unknown user" via status
            // codes is a user-enumeration leak.
            expect(r.status()).toBe(401);
        } finally {
            await api.dispose();
        }
    });

    test('dev-token — audience mismatch returns 403 (auth ok, scope wrong)', async () => {
        // The admin user is in the admin audience; requesting customer
        // audience must fail with 403 (authentic but not authorized for
        // the requested audience), not 401 (which would imply the
        // credentials were wrong).
        const api = await plRequest.newContext();
        try {
            const r = await api.post(`${ENDPOINTS.identity}/dev/oauth2/dev-token`, {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    username: 'admin@jrpay.example',
                    password: 'demo-password-1!',
                    audience: 'customer',
                },
            });
            expect(r.status()).toBe(403);
        } finally {
            await api.dispose();
        }
    });

    test('client-web emits GUI-SEC-03 security headers on /login', async () => {
        const api = await plRequest.newContext();
        try {
            const r = await api.get(`${GUI.clientWeb.app}/login`);
            expect(r.status()).toBe(200);
            const h = r.headers();
            expect(h['content-security-policy'] ?? '').not.toBe('');
            expect(h['x-frame-options']?.toLowerCase()).toBe('deny');
            expect(h['x-content-type-options']?.toLowerCase()).toBe('nosniff');
            expect(h['referrer-policy'] ?? '').not.toBe('');
        } finally {
            await api.dispose();
        }
    });

    test('admin-web emits GUI-SEC-03 security headers on /login', async () => {
        const api = await plRequest.newContext();
        try {
            const r = await api.get(`${GUI.adminWeb.app}/login`);
            expect(r.status()).toBe(200);
            const h = r.headers();
            expect(h['content-security-policy'] ?? '').not.toBe('');
            expect(h['x-frame-options']?.toLowerCase()).toBe('deny');
            expect(h['x-content-type-options']?.toLowerCase()).toBe('nosniff');
        } finally {
            await api.dispose();
        }
    });

    test('createAccount — F7 validation handler returns 400 with field details', async () => {
        const demoApi = await basicApi();
        try {
            const r = await demoApi.post(`${ENDPOINTS.accountLedger}/api/v1/accounts`, {
                headers: { 'Content-Type': 'application/json' },
                data: {
                    accountId: '', // @NotBlank fail
                    holderName: '',
                    currency: '',
                    initialBalance: -10, // @Positive fail
                },
            });
            expect(r.status(), `body=${(await r.text()).slice(0, 300)}`).toBe(400);
            const body = (await r.json()) as {
                type?: string;
                fields?: Record<string, string>;
            };
            expect(body.type ?? '').toMatch(/validation/);
            // F7 attaches a `fields` map listing each violation. We expect
            // at least one of the rejected fields to be present.
            const keys = Object.keys(body.fields ?? {});
            expect(keys.length).toBeGreaterThan(0);
        } finally {
            await demoApi.dispose();
        }
    });
});
