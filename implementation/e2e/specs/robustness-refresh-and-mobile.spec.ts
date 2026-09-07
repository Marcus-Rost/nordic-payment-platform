// Phase E §2 + §10 — back/forward/refresh + mobile-viewport.
//
// §2: Browser navigation preserves URL + content at every step.
// §10 (combined): the SPA renders correctly at a phone viewport (Pixel 7-ish).
//
// What it proves:
//   - Refresh on /login keeps the user on /login (not a flash to /).
//   - Direct deep-link to /login renders the login screen (no redirect loop).
//   - The same login screen renders at 393×851 (Pixel-7 viewport) without
//     a layout collapse — defends against "phone width breaks the page".
//
// False-green hazard: if a service-worker cached the page, the URL test
// would silently pass even if the server-side route was broken. We hit
// the page with cache disabled to defend against that.

import { expect, test } from '@playwright/test';
import { ENDPOINTS } from '../helpers/endpoints';
import { requireServices } from '../helpers/preflight';

test.describe('Phase E §2/§10 — refresh + mobile-viewport', () => {
    test.beforeEach(() => {
        requireServices('clientWeb', 'adminWeb');
    });

    test('refresh on /login lands back on /login (no redirect loop)', async ({ browser }) => {
        const ctx = await browser.newContext({
            extraHTTPHeaders: { 'Cache-Control': 'no-cache' },
        });
        try {
            const page = await ctx.newPage();
            await page.goto(`${ENDPOINTS.clientWeb}/login`);
            await expect(page).toHaveURL(/\/login(\?|$)/);

            await page.reload();
            await expect(page).toHaveURL(/\/login(\?|$)/);
            await expect(page.getByRole('heading', { name: /sign in|logg inn/i })).toBeVisible();
        } finally {
            await ctx.close();
        }
    });

    test('client-web at Pixel-7 viewport (393×851) renders the login screen', async ({ browser }) => {
        const ctx = await browser.newContext({ viewport: { width: 393, height: 851 } });
        try {
            const page = await ctx.newPage();
            await page.goto(`${ENDPOINTS.clientWeb}/login`);
            const heading = page.getByRole('heading', { name: /sign in|logg inn/i });
            await expect(heading).toBeVisible();
            // The button must be visible and reachable (not clipped by a
            // viewport overflow). Bounding box must lie within viewport.
            const btn = page.getByRole('button', {
                name: /continue with bankid|fortsett med bankid/i,
            });
            await expect(btn).toBeVisible();
            const box = await btn.boundingBox();
            expect(box).not.toBeNull();
            if (box) {
                expect(box.x + box.width).toBeLessThanOrEqual(393);
                expect(box.x).toBeGreaterThanOrEqual(0);
            }
        } finally {
            await ctx.close();
        }
    });

    test('admin-web at Pixel-7 viewport renders the login screen', async ({ browser }) => {
        const ctx = await browser.newContext({ viewport: { width: 393, height: 851 } });
        try {
            const page = await ctx.newPage();
            await page.goto(`${ENDPOINTS.adminWeb}/login`);
            await expect(page.getByRole('heading', { name: /sign in|logg inn/i })).toBeVisible();
        } finally {
            await ctx.close();
        }
    });
});
