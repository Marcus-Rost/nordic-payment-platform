// Token issuance via identity's /dev/oauth2/dev-token endpoint. Active under
// the `docker`/`demo`/`smoke` Spring profiles only — production deployments
// must NEVER expose this. See DevTokenController.java in jrpay-identity-service.

import { request as plRequest, type APIRequestContext } from '@playwright/test';
import { DEMO_USERS, ENDPOINTS } from './endpoints';

export interface DevToken {
    accessToken: string;
    tokenType: string;
    expiresIn: number;
    audience: 'admin' | 'customer';
}

export async function issueToken(
    api: APIRequestContext,
    user: keyof typeof DEMO_USERS,
): Promise<DevToken> {
    const u = DEMO_USERS[user];
    const res = await api.post(`${ENDPOINTS.identity}/dev/oauth2/dev-token`, {
        headers: { 'Content-Type': 'application/json' },
        data: {
            username: u.username,
            password: u.password,
            audience: u.audience,
        },
    });
    if (!res.ok()) {
        const body = await res.text();
        throw new Error(
            `dev-token failed for ${user}: ${res.status()} ${res.statusText()} body=${body.slice(0, 300)}`,
        );
    }
    const json = (await res.json()) as {
        access_token: string;
        token_type: string;
        expires_in: number;
    };
    return {
        accessToken: json.access_token,
        tokenType: json.token_type,
        expiresIn: json.expires_in,
        audience: u.audience,
    };
}

/** Acquire a fresh APIRequestContext bound with a Bearer token for `user`. */
export async function authedApi(user: keyof typeof DEMO_USERS): Promise<APIRequestContext> {
    const bootstrap = await plRequest.newContext();
    const token = await issueToken(bootstrap, user);
    await bootstrap.dispose();
    return await plRequest.newContext({
        extraHTTPHeaders: { Authorization: `Bearer ${token.accessToken}` },
    });
}
