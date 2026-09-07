// OAuth2 authorization-code + PKCE flow against the JRPay identity service.
// The customer SPA uses BankID-simulated identity assertion; the identity
// service handles BankID redirect upstream.

import { api } from '../api/client';
import {
    clearVerifier,
    newNonce,
    newPkceChallenge,
    newState,
    readVerifier,
    stashVerifier,
} from './pkce';

const CLIENT_ID = (import.meta.env.VITE_OAUTH_CLIENT_ID as string) || 'jrpay-client-web';
const IDENTITY_BASE = (import.meta.env.VITE_IDENTITY_BASE as string) || '';
const REDIRECT_URI = (import.meta.env.VITE_OAUTH_REDIRECT_URI as string) ||
    `${window.location.origin}/oauth/callback`;
const SCOPE = (import.meta.env.VITE_OAUTH_SCOPE as string) ||
    'openid customer:read customer:write';

export interface TokenResponse {
    access_token: string;
    refresh_token?: string;
    token_type: string;
    expires_in: number;
    scope?: string;
    id_token?: string;
}

export async function beginLogin(returnTo: string = '/'): Promise<void> {
    const { verifier, challenge } = await newPkceChallenge();
    const state = newState();
    const nonce = newNonce();
    stashVerifier(verifier, state, nonce, returnTo);

    const params = new URLSearchParams({
        response_type: 'code',
        client_id: CLIENT_ID,
        redirect_uri: REDIRECT_URI,
        scope: SCOPE,
        state,
        nonce,
        code_challenge: challenge,
        code_challenge_method: 'S256',
    });
    window.location.assign(`${IDENTITY_BASE}/oauth2/authorize?${params.toString()}`);
}

export async function completeLogin(code: string, state: string): Promise<TokenResponse> {
    const stash = readVerifier();
    if (!stash || stash.state !== state) {
        clearVerifier();
        throw new Error('OAuth state mismatch — restart sign-in.');
    }
    const body = new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        client_id: CLIENT_ID,
        redirect_uri: REDIRECT_URI,
        code_verifier: stash.verifier,
    });
    const res = await fetch(`${IDENTITY_BASE}/oauth2/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    if (!res.ok) {
        clearVerifier();
        throw new Error(`Token exchange failed (${res.status})`);
    }
    clearVerifier();
    return (await res.json()) as TokenResponse;
}

export async function refreshAccessToken(refreshToken: string): Promise<TokenResponse> {
    const body = new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: refreshToken,
        client_id: CLIENT_ID,
    });
    const res = await fetch(`${IDENTITY_BASE}/oauth2/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    if (!res.ok) throw new Error(`Refresh failed (${res.status})`);
    return (await res.json()) as TokenResponse;
}

export async function revokeRefreshToken(refreshToken: string): Promise<void> {
    await api('/oauth2/revoke', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ token: refreshToken, client_id: CLIENT_ID }).toString(),
    }).catch(() => undefined);
}

export function readReturnTo(): string {
    const stash = readVerifier();
    return stash?.returnTo ?? '/';
}
