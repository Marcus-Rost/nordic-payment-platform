// Web equivalent of `android-app/.../uitest/TestAuth.kt`.
//
// Issues a customer Bearer token via identity's `/dev/oauth2/dev-token`
// endpoint and injects it into client-web's `sessionStorage` so the
// AuthProvider picks it up at app boot. Skips the BankID round-trip
// entirely.
//
// Storage shape (from client-web/src/auth/AuthContext.tsx):
//   sessionStorage["jrpay.client.session"] = JSON.stringify({
//     accessToken, refreshToken, issuedAt, expiresAt, payload
//   })
//
// `payload` is the decoded JWT (claim object) — re-decoded server-side
// JWTs preserve all claims, so we can decode in-test and embed.

import type { BrowserContext, Page } from '@playwright/test';
import { issueToken } from './auth';
import { ENDPOINTS } from './endpoints';

const STORAGE_KEY = 'jrpay.client.session';

interface DecodedJwt {
    sub?: string;
    exp?: number;
    aud?: string | string[];
    scope?: string;
    acr?: string;
    jrpay_uid?: string;
    [k: string]: unknown;
}

function decodeJwt(token: string): DecodedJwt {
    const [, payload] = token.split('.');
    const padded = payload + '==='.slice((payload.length + 3) % 4);
    const json = Buffer.from(padded, 'base64url').toString('utf8');
    return JSON.parse(json) as DecodedJwt;
}

/**
 * Issue a customer Bearer token and seed it into the page's sessionStorage
 * before any client-web JS runs. Subsequent navigation to a guarded route
 * (/payments, /mandates, etc.) hits AuthGuard with a non-null session and
 * renders the AppShell + page content.
 */
export async function seedCustomerSession(context: BrowserContext): Promise<void> {
    const { request: bootstrap } = await import('@playwright/test');
    const api = await bootstrap.newContext();
    let token;
    try {
        token = await issueToken(api, 'customer');
    } finally {
        await api.dispose();
    }

    const payload = decodeJwt(token.accessToken);
    const issuedAt = Math.floor(Date.now() / 1000);
    const session = {
        accessToken: token.accessToken,
        refreshToken: null,
        issuedAt,
        expiresAt: payload.exp ?? issuedAt + 900,
        payload,
    };

    // addInitScript runs on every navigation BEFORE the SPA's first script
    // — so AuthProvider's `load()` reads the session on its initial useState.
    await context.addInitScript(
        ([key, value]: [string, string]) => {
            sessionStorage.setItem(key, value);
        },
        [STORAGE_KEY, JSON.stringify(session)] as [string, string],
    );
}

/** Navigate to a path on client-web after seeding. The function is the
 *  per-spec convenience that combines `seedCustomerSession` + `page.goto`. */
export async function gotoAuthedClientWeb(page: Page, path: string): Promise<void> {
    await seedCustomerSession(page.context());
    await page.goto(`${ENDPOINTS.clientWeb}${path}`);
}
