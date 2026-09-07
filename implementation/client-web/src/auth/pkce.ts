// PKCE per RFC 7636. Verifier is stashed in sessionStorage for the lifetime
// of the BankID redirect; the code challenge is sent in the authorize call
// and the verifier is exchanged for the access token at /oauth2/token.

const VERIFIER_KEY = 'jrpay.client.pkce.verifier';
const STATE_KEY = 'jrpay.client.pkce.state';
const NONCE_KEY = 'jrpay.client.pkce.nonce';
const RETURN_TO_KEY = 'jrpay.client.pkce.returnTo';

function base64UrlEncode(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]!);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function newPkceChallenge(): Promise<{ verifier: string; challenge: string }> {
    const verifier = base64UrlEncode(crypto.getRandomValues(new Uint8Array(32)));
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    const challenge = base64UrlEncode(new Uint8Array(digest));
    return { verifier, challenge };
}

export function newState(): string {
    return base64UrlEncode(crypto.getRandomValues(new Uint8Array(16)));
}

export function newNonce(): string {
    return base64UrlEncode(crypto.getRandomValues(new Uint8Array(16)));
}

export function stashVerifier(
    verifier: string,
    state: string,
    nonce: string,
    returnTo?: string
): void {
    sessionStorage.setItem(VERIFIER_KEY, verifier);
    sessionStorage.setItem(STATE_KEY, state);
    sessionStorage.setItem(NONCE_KEY, nonce);
    if (returnTo) sessionStorage.setItem(RETURN_TO_KEY, returnTo);
}

export function readVerifier(): {
    verifier: string;
    state: string;
    nonce: string;
    returnTo: string | null;
} | null {
    const verifier = sessionStorage.getItem(VERIFIER_KEY);
    const state = sessionStorage.getItem(STATE_KEY);
    const nonce = sessionStorage.getItem(NONCE_KEY);
    if (!verifier || !state || !nonce) return null;
    return { verifier, state, nonce, returnTo: sessionStorage.getItem(RETURN_TO_KEY) };
}

export function clearVerifier(): void {
    sessionStorage.removeItem(VERIFIER_KEY);
    sessionStorage.removeItem(STATE_KEY);
    sessionStorage.removeItem(NONCE_KEY);
    sessionStorage.removeItem(RETURN_TO_KEY);
}
