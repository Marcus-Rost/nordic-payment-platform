// Playwright config for the JRPay customer-web E2E suite.
//
// Local invocation:
//   1. `make compose-up` from repo root
//   2. `npm run build && npm run preview &`
//   3. `npx playwright install chromium`
//   4. `npx playwright test`

import type { PlaywrightTestConfig } from '@playwright/test';

const config: PlaywrightTestConfig = {
    testDir: './e2e',
    timeout: 30_000,
    expect: { timeout: 10_000 },
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    reporter: [['list'], ['html', { open: 'never' }]],
    use: {
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
        trace: 'on-first-retry',
        video: 'retain-on-failure',
        screenshot: 'only-on-failure',
        viewport: { width: 1280, height: 800 },
    },
    projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
};

export default config;
