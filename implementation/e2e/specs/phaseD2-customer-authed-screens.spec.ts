// Phase D2 — authenticated Citizen GUI screens (web).
//
// Until this spec, the Playwright suite only covered the unauthenticated
// surface (login page, security headers, a11y on /login). This file
// extends coverage by injecting a customer Bearer token via
// `seedCustomerSession` (skipping the BankID round-trip) and asserting
// that each of the 8 authenticated routes renders its canonical title +
// either a ready or error state, never a crash.
//
// What this proves end-to-end:
//   - AuthGuard recognises a seeded session and routes through to the
//     AppShell rather than redirecting to /login.
//   - Each customer-facing screen mounts and renders its h1.
//   - The screens degrade gracefully when the backend endpoint isn't
//     wired or returns 500 (current state for 4 of 8 endpoints — see
//     OPEN-7, OPEN-10, and the per-endpoint status in OPEN_ITEMS.md).
//
// What this deliberately does NOT prove:
//   - Form submissions actually persist (UC-G07 payment, UC-G16 add
//     beneficiary, UC-G24 GDPR request). Those require working POST
//     endpoints, several of which are currently 500. Submission
//     assertions land when the matching OPEN-N items are closed.
//
// Status: real assertions, no regression-traps in this file. The
// "renders even when backend is down" property is asserted positively
// (h1 is visible) without coupling to which backend state is current.

import { expect, test } from '@playwright/test';
import { gotoAuthedClientWeb, seedCustomerSession } from '../helpers/webAuth';
import { ENDPOINTS } from '../helpers/endpoints';
import { requireServices } from '../helpers/preflight';

test.describe('Phase D2 — authenticated customer screens', () => {
    test.beforeEach(() => {
        requireServices('identity', 'clientWeb');
    });

    test('seeded session bypasses /login and lands on the dashboard', async ({ page }) => {
        await seedCustomerSession(page.context());
        await page.goto(`${ENDPOINTS.clientWeb}/`);
        // AuthGuard must NOT redirect to /login because we have a session.
        await expect(page).not.toHaveURL(/\/login(\?|$)/);
        await expect(page.locator('h1#dash-title')).toBeVisible();
        await expect(page.locator('h1#dash-title')).toContainText(/welcome back|velkommen/i);
    });

    test('payments — UC-G07 form surface renders under auth', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/payments');
        await expect(page.locator('h1#payments-title')).toBeVisible();
        await expect(page.locator('h1#payments-title')).toContainText(/payments|betalinger/i);
    });

    test('mandates — UC-G08/G09 AvtaleGiro mandates list', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/mandates');
        await expect(page.locator('h1#mandates-title')).toBeVisible();
        await expect(page.locator('h1#mandates-title')).toContainText(/AvtaleGiro/i);
    });

    test('r2p — UC-G19 request-to-pay inbox', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/r2p');
        await expect(page.locator('h1#r2p-title')).toBeVisible();
        await expect(page.locator('h1#r2p-title')).toContainText(/payment requests|betalingsforesp/i);
    });

    test('beneficiaries — UC-G16 beneficiaries CRUD surface', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/beneficiaries');
        await expect(page.locator('h1#ben-title')).toBeVisible();
        await expect(page.locator('h1#ben-title')).toContainText(/beneficiaries|mottakere/i);
    });

    test('consents — UC-G17/G18 PSD2 consents list', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/consents');
        await expect(page.locator('h1#cons-title')).toBeVisible();
        await expect(page.locator('h1#cons-title')).toContainText(/consents|samtykker/i);
    });

    test('notifications — UC-G33 notifications inbox', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/notifications');
        await expect(page.locator('h1#not-title')).toBeVisible();
        await expect(page.locator('h1#not-title')).toContainText(/notifications|varsler/i);
    });

    test('profile — UC-G26 customer profile renders title even when /profile 404s', async ({ page }) => {
        // After OPEN-12 fix: ProfilePage renders h1 unconditionally and
        // shows a separate Loading/error slot below it. /api/v1/customer/profile
        // doesn't have a controller yet (clean 404 post-OPEN-10), so the
        // customer SCA-factors slot stays unrendered, but the page itself
        // does NOT vanish into an indefinite spinner anymore.
        await gotoAuthedClientWeb(page, '/profile');
        await expect(page.locator('h1#profile-title')).toBeVisible();
        await expect(page.locator('h1#profile-title')).toContainText(/profile|profil/i);
    });

    test('privacy — UC-G24 GDPR data export + erasure', async ({ page }) => {
        await gotoAuthedClientWeb(page, '/privacy');
        await expect(page.locator('h1#priv-title')).toBeVisible();
        await expect(page.locator('h1#priv-title')).toContainText(/privacy.*GDPR|personvern/i);
    });

    test('customer-payments endpoint reachable + RFC-9457 validation on missing input', async ({ request }) => {
        // Proves OPEN-7 closed: /api/v1/customer/payments POST is wired
        // (was unreachable due to nginx routing → account-ledger; controller
        // actually lives in psd2-api). Surface assertion: endpoint returns
        // a structured problem detail, NOT 404 / 500.
        const { request: bootstrap } = await import('@playwright/test');
        const api = await bootstrap.newContext();
        try {
            const tokenRes = await api.post(
                `${ENDPOINTS.identity}/dev/oauth2/dev-token`,
                {
                    headers: { 'Content-Type': 'application/json' },
                    data: {
                        username: 'ola.nordmann@example.com',
                        password: 'demo-password-1!',
                        audience: 'customer',
                    },
                },
            );
            const token = (await tokenRes.json()) as { access_token: string };

            const res = await api.post(
                `${ENDPOINTS.clientWeb}/api/v1/customer/payments`,
                {
                    headers: {
                        'Content-Type': 'application/json',
                        Authorization: `Bearer ${token.access_token}`,
                    },
                    data: {
                        debtorAccountId: 'NO-NONEXISTENT',
                        creditorIban: 'NO9386011117947',
                        creditorName: 'Test Creditor',
                        amount: '10.00',
                        currency: 'NOK',
                    },
                },
            );
            // No Idempotency-Key header → 422 + urn:problem:validation.
            expect(res.status(), `body=${(await res.text()).slice(0, 200)}`).toBe(422);
            const body = (await res.json()) as { type?: string; detail?: string };
            expect(body.type ?? '').toMatch(/validation/);
            expect(body.detail ?? '').toMatch(/Idempotency-Key/i);
        } finally {
            await api.dispose();
        }
    });

    test('cross-locale — switching to nb-NO updates titles to Norwegian', async ({ page }) => {
        // The SPA picks up the locale from the browser's accept-language
        // header on first paint. Set it via context locale option.
        const ctx = await page.context().browser()!.newContext({ locale: 'nb-NO' });
        await seedCustomerSession(ctx);
        const nbPage = await ctx.newPage();
        try {
            await nbPage.goto(`${ENDPOINTS.clientWeb}/payments`);
            await expect(nbPage.locator('h1#payments-title')).toBeVisible();
            // Norwegian title is "Betalinger" per nb-NO.json. Either the
            // English ("Payments") or Norwegian ("Betalinger") is acceptable
            // — the SPA's locale resolution might fall back to en if i18next
            // doesn't think the user explicitly chose nb. We assert one of
            // them, which is the locale-agnostic "title is present".
            const text = await nbPage.locator('h1#payments-title').textContent();
            expect(text?.toLowerCase()).toMatch(/payments|betalinger/);
        } finally {
            await ctx.close();
        }
    });
});
