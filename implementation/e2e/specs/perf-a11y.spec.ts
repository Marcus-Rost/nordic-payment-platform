// Phase F.1 — a11y scan via axe-core. Per E2E_TESTING_GUIDE.md §F.1, fail
// only on SERIOUS / CRITICAL violations — minor / moderate are noise that
// turns the suite into background static.
//
// What it proves: the public-surface login pages of both SPAs are free of
// accessibility regressions at the SERIOUS / CRITICAL severity. Lower
// severities are reported but not enforced.
//
// What it deliberately does NOT prove: a11y on authenticated screens
// (would require driving the OAuth flow). Those are deferred until a
// `seedAuthenticatedSession()` helper exists.

import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { ENDPOINTS } from '../helpers/endpoints';
import { requireServices } from '../helpers/preflight';

const SEVERE = ['serious', 'critical'] as const;

test.describe('Phase F.1 — a11y scan (SERIOUS+CRITICAL only)', () => {
    test.beforeEach(() => {
        requireServices('clientWeb', 'adminWeb');
    });

    test('client-web /login — no SERIOUS or CRITICAL axe violations', async ({ page }) => {
        await page.goto(`${ENDPOINTS.clientWeb}/login`);
        const results = await new AxeBuilder({ page }).analyze();
        const blocking = results.violations.filter((v) =>
            SEVERE.includes(v.impact as (typeof SEVERE)[number]),
        );
        if (blocking.length > 0) {
            // Helpful diagnostic in test output.
            // eslint-disable-next-line no-console
            console.log(
                'a11y violations on client-web /login:',
                blocking.map((v) => `${v.id} (${v.impact}): ${v.help}`),
            );
        }
        expect(blocking, 'no SERIOUS/CRITICAL a11y violations').toHaveLength(0);
    });

    test('admin-web /login — no SERIOUS or CRITICAL axe violations', async ({ page }) => {
        await page.goto(`${ENDPOINTS.adminWeb}/login`);
        const results = await new AxeBuilder({ page }).analyze();
        const blocking = results.violations.filter((v) =>
            SEVERE.includes(v.impact as (typeof SEVERE)[number]),
        );
        if (blocking.length > 0) {
            // eslint-disable-next-line no-console
            console.log(
                'a11y violations on admin-web /login:',
                blocking.map((v) => `${v.id} (${v.impact}): ${v.help}`),
            );
        }
        expect(blocking, 'no SERIOUS/CRITICAL a11y violations').toHaveLength(0);
    });
});
