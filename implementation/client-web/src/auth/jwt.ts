// Decodes a JWT payload without signature verification. The browser does not
// have the JWKS public key locally; signature verification is the resource
// server's job. We only inspect the payload to render the customer identity
// and audience locally.

export interface JwtPayload {
    iss?: string;
    sub?: string;
    aud?: string | string[];
    exp?: number;
    iat?: number;
    nbf?: number;
    jti?: string;
    scope?: string;
    jrpay_uid?: string;
    acr?: string;
    [extension: string]: unknown;
}

export function decodeJwt(token: string): JwtPayload | null {
    const parts = token.split('.');
    if (parts.length < 2) return null;
    try {
        const payload = parts[1]!.replace(/-/g, '+').replace(/_/g, '/');
        const padded = payload + '='.repeat((4 - (payload.length % 4)) % 4);
        const json = atob(padded);
        return JSON.parse(decodeURIComponent(escape(json))) as JwtPayload;
    } catch {
        return null;
    }
}

export function isExpired(payload: JwtPayload | null, skewSeconds = 30): boolean {
    if (!payload?.exp) return true;
    const now = Math.floor(Date.now() / 1000);
    return now >= payload.exp - skewSeconds;
}

export function expiresInSeconds(payload: JwtPayload | null): number {
    if (!payload?.exp) return 0;
    const now = Math.floor(Date.now() / 1000);
    return Math.max(0, payload.exp - now);
}

export function audienceList(payload: JwtPayload | null): string[] {
    if (!payload?.aud) return [];
    return Array.isArray(payload.aud) ? payload.aud : [payload.aud];
}

export function isCustomer(payload: JwtPayload | null): boolean {
    return audienceList(payload).includes('customer');
}

export function scopes(payload: JwtPayload | null): string[] {
    if (!payload?.scope) return [];
    return payload.scope.split(/\s+/).filter(Boolean);
}

export function hasScope(payload: JwtPayload | null, scope: string): boolean {
    return scopes(payload).includes(scope);
}
