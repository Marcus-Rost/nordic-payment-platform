import { describe, expect, it } from 'vitest';
import {
    audienceList,
    decodeJwt,
    expiresInSeconds,
    hasScope,
    isAdmin,
    isExpired,
    scopes,
} from '../jwt';

function jwt(payload: Record<string, unknown>): string {
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const body = b64url(JSON.stringify(payload));
    return `${header}.${body}.signature-not-validated`;
}

function b64url(s: string): string {
    return btoa(unescape(encodeURIComponent(s)))
        .replace(/=/g, '')
        .replace(/\+/g, '-')
        .replace(/\//g, '_');
}

describe('decodeJwt', () => {
    it('returns null on malformed input', () => {
        expect(decodeJwt('garbage')).toBeNull();
        // Has 2 segments but the body is not base64url JSON.
        expect(decodeJwt('only.one')).toBeNull();
        expect(decodeJwt('')).toBeNull();
    });

    it('decodes payload claims', () => {
        const t = jwt({
            sub: 'op-123',
            aud: 'admin',
            exp: 1_700_000_000,
            scope: 'fraud:read fraud:manage',
            jrpay_uid: 'op-123',
            role: 'FRAUD_ANALYST',
            acr: 'loa3-admin',
        });
        const p = decodeJwt(t);
        expect(p?.sub).toBe('op-123');
        expect(p?.role).toBe('FRAUD_ANALYST');
        expect(p?.acr).toBe('loa3-admin');
    });
});

describe('expiry helpers', () => {
    it('isExpired returns true past the exp claim', () => {
        const past = { exp: Math.floor(Date.now() / 1000) - 10 };
        expect(isExpired(past)).toBe(true);
    });
    it('isExpired returns false when exp is sufficiently far in the future', () => {
        const future = { exp: Math.floor(Date.now() / 1000) + 600 };
        expect(isExpired(future)).toBe(false);
    });
    it('isExpired honours the skew window', () => {
        const close = { exp: Math.floor(Date.now() / 1000) + 10 };
        expect(isExpired(close, 30)).toBe(true);
    });
    it('expiresInSeconds counts down to zero', () => {
        expect(expiresInSeconds(null)).toBe(0);
        expect(expiresInSeconds({ exp: Math.floor(Date.now() / 1000) - 1 })).toBe(0);
        expect(
            expiresInSeconds({ exp: Math.floor(Date.now() / 1000) + 60 })
        ).toBeGreaterThan(0);
    });
});

describe('aud / role / scope', () => {
    it('audienceList normalises string vs array form', () => {
        expect(audienceList({ aud: 'admin' })).toEqual(['admin']);
        expect(audienceList({ aud: ['admin', 'audit'] })).toEqual(['admin', 'audit']);
        expect(audienceList(null)).toEqual([]);
    });
    it('isAdmin requires aud=admin', () => {
        expect(isAdmin({ aud: 'admin' })).toBe(true);
        expect(isAdmin({ aud: ['customer'] })).toBe(false);
    });
    it('scopes splits on whitespace', () => {
        expect(scopes({ scope: 'a b\tc\nd' })).toEqual(['a', 'b', 'c', 'd']);
        expect(scopes(null)).toEqual([]);
    });
    it('hasScope is exact match', () => {
        const p = { scope: 'fraud:read fraud:manage' };
        expect(hasScope(p, 'fraud:read')).toBe(true);
        expect(hasScope(p, 'fraud:write')).toBe(false);
    });
});
