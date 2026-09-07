// OAuth2 authorization-code + PKCE driver against jrpay-identity-service.
//
// Flow (UC-G30, UC-G50):
//   1. Login button → beginLogin()
//      - generates verifier + challenge + state + nonce
//      - redirects browser to /oauth2/authorize
//   2. Identity redirects back to /oauth/callback?code=...
//   3. CallbackPage hands code + verifier + state to completeLogin()
//      - POST /oauth2/token (grant_type=authorization_code)
//      - returns { access_token, refresh_token, expires_in }
//   4. AuthContext stores the session.
//
// The dev token endpoint (Phase 2N) is reserved for smoke tests; real users
// always go through this PKCE flow. In dev, the OAuth client `jrpay-admin-web`
// (V002 seed) lists `http://localhost:5174/oauth/callback` as an allowed
// redirect — the same URI the SPA uses below.

import { clearVerifier, newNonce, newPkceChallenge, newState, stashVerifier } from './pkce';

export interface OAuthConfig {
    issuer: string;
    clientId: string;
    redirectUri: string;
    scopes: string[];
}

export const OAUTH_CONFIG: OAuthConfig = {
    issuer: import.meta.env.VITE_IDENTITY_BASE ?? '',
    clientId: import.meta.env.VITE_OAUTH_CLIENT_ID ?? 'jrpay-admin-web',
    redirectUri:
        import.meta.env.VITE_OAUTH_REDIRECT_URI ??
        `${window.location.origin}/oauth/callback`,
    scopes: (import.meta.env.VITE_OAUTH_SCOPES ?? 'openid profile')
        .split(/\s+/)
        .filter(Boolean),
};

export async function beginLogin(returnTo: string = '/'): Promise<void> {
    const { verifier, challenge } = await newPkceChallenge();
    const state = newState();
    const nonce = newNonce();
    stashVerifier(verifier, state, nonce);
    sessionStorage.setItem('jrpay.admin.returnTo', returnTo);

    const authorizeUrl = new URL(`${OAUTH_CONFIG.issuer}/oauth2/authorize`);
    authorizeUrl.searchParams.set('response_type', 'code');
    authorizeUrl.searchParams.set('client_id', OAUTH_CONFIG.clientId);
    authorizeUrl.searchParams.set('redirect_uri', OAUTH_CONFIG.redirectUri);
    authorizeUrl.searchParams.set('scope', OAUTH_CONFIG.scopes.join(' '));
    authorizeUrl.searchParams.set('state', state);
    authorizeUrl.searchParams.set('nonce', nonce);
    authorizeUrl.searchParams.set('code_challenge', challenge);
    authorizeUrl.searchParams.set('code_challenge_method', 'S256');
    window.location.assign(authorizeUrl.toString());
}

export interface TokenResponse {
    access_token: string;
    refresh_token?: string;
    token_type: string;
    expires_in: number;
    scope?: string;
}

export async function completeLogin(
    code: string,
    state: string,
    storedState: string,
    verifier: string
): Promise<TokenResponse> {
    if (state !== storedState) {
        throw new Error('OAuth2 state mismatch — possible CSRF / replay');
    }
    const body = new URLSearchParams();
    body.set('grant_type', 'authorization_code');
    body.set('code', code);
    body.set('client_id', OAUTH_CONFIG.clientId);
    body.set('redirect_uri', OAUTH_CONFIG.redirectUri);
    body.set('code_verifier', verifier);

    const response = await fetch(`${OAUTH_CONFIG.issuer}/oauth2/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    if (!response.ok) {
        const detail = await response.text();
        throw new Error(`Token exchange failed (${response.status}): ${detail}`);
    }
    clearVerifier();
    return (await response.json()) as TokenResponse;
}

export async function refreshAccessToken(refreshToken: string): Promise<TokenResponse> {
    const body = new URLSearchParams();
    body.set('grant_type', 'refresh_token');
    body.set('refresh_token', refreshToken);
    body.set('client_id', OAUTH_CONFIG.clientId);
    const response = await fetch(`${OAUTH_CONFIG.issuer}/oauth2/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    if (!response.ok) {
        const detail = await response.text();
        throw new Error(`Refresh failed (${response.status}): ${detail}`);
    }
    return (await response.json()) as TokenResponse;
}

export async function revokeRefreshToken(refreshToken: string): Promise<void> {
    const body = new URLSearchParams();
    body.set('token', refreshToken);
    body.set('token_type_hint', 'refresh_token');
    body.set('client_id', OAUTH_CONFIG.clientId);
    await fetch(`${OAUTH_CONFIG.issuer}/oauth2/revoke`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
}
