// Smoke E2E — verifies the customer SPA serves login and redirects
// unauthenticated traffic. Requires the full backend stack via docker-compose
// plus `npm run preview` (see playwright.config.ts header).

import { expect, test } from '@playwright/test';

test('serves the customer SPA and redirects unauthenticated traffic to /login', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByRole('heading', { name: /sign in|logg inn/i })).toBeVisible();
    await expect(
        page.getByRole('button', { name: /continue with bankid|fortsett med bankid/i })
    ).toBeVisible();
});

test('renders the i18n bundle for the locale selected by the browser', async ({ page }) => {
    await page.goto('/login');
    const html = await page.content();
    expect(html).toMatch(/Sign in|Logg inn/);
});
