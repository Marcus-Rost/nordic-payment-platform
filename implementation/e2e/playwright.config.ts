// JRPay cross-client E2E configuration.
//
// This package is intentionally separate from `admin-web/e2e/` and
// `client-web/e2e/` — it tests cross-client invariants ("customer submits
// a payment, admin sees it") that don't belong to either client's own
// test suite. See README.md for the runbook.

import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './specs',

    timeout: 60_000,
    expect: { timeout: 15_000 },

    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    workers: 1,

    reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],

    globalSetup: './helpers/globalSetup.ts',

    use: {
        trace: 'on-first-retry',
        video: 'retain-on-failure',
        screenshot: 'only-on-failure',
        viewport: { width: 1280, height: 800 },
        actionTimeout: 10_000,
        navigationTimeout: 20_000,
    },

    projects: [
        {
            name: 'chromium',
            use: { browserName: 'chromium' },
        },
    ],
});
