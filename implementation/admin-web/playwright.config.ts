// Playwright config for the JRPay admin SPA E2E suite.
//
// E2E tests assume the full backend stack is already up via the project's
// docker-compose (`make compose-up` from the repo root) and that the admin
// SPA has been built and is being served by `npm run preview`. CI wires this
// up via the `e2e` job in `.github/workflows/admin-web.yml` (added in 3.14).
//
// Local invocation:
//   1. `make compose-up` from repo root
//   2. `npm run build && npm run preview &` here
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
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5174',
        trace: 'on-first-retry',
        video: 'retain-on-failure',
        screenshot: 'only-on-failure',
        viewport: { width: 1280, height: 800 },
    },
    projects: [
        {
            name: 'chromium',
            use: { browserName: 'chromium' },
        },
    ],
};

export default config;
