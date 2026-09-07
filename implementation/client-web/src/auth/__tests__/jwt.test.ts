import { describe, expect, it } from 'vitest';
import {
    audienceList,
    decodeJwt,
    expiresInSeconds,
    hasScope,
    isCustomer,
    isExpired,
    scopes,
} from '../jwt';

function jwt(payload: Record<string, unknown>): string {
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const body = b64url(JSON.stringify(payload));
    return `${header}.${body}.sig`;
}
function b64url(s: string): string {
    return btoa(unescape(encodeURIComponent(s)))
        .replace(/=/g, '')
        .replace(/\+/g, '-')
        .replace(/\//g, '_');
}

describe('decodeJwt', () => {
    it('returns null on garbage', () => {
        expect(decodeJwt('garbage')).toBeNull();
        expect(decodeJwt('')).toBeNull();
    });
    it('decodes claims', () => {
        const t = jwt({ sub: 'cust-1', aud: 'customer', exp: 1_700_000_000 });
        expect(decodeJwt(t)?.sub).toBe('cust-1');
    });
});

describe('expiry / aud / scopes', () => {
    it('isExpired honours skew', () => {
        const close = { exp: Math.floor(Date.now() / 1000) + 10 };
        expect(isExpired(close, 30)).toBe(true);
    });
    it('expiresInSeconds counts to zero', () => {
        expect(expiresInSeconds(null)).toBe(0);
        expect(expiresInSeconds({ exp: Math.floor(Date.now() / 1000) - 1 })).toBe(0);
    });
    it('audienceList normalises both string and array', () => {
        expect(audienceList({ aud: 'customer' })).toEqual(['customer']);
        expect(audienceList({ aud: ['customer', 'admin'] })).toEqual(['customer', 'admin']);
    });
    it('isCustomer requires aud=customer', () => {
        expect(isCustomer({ aud: 'customer' })).toBe(true);
        expect(isCustomer({ aud: 'admin' })).toBe(false);
    });
    it('scopes splits whitespace and hasScope is exact', () => {
        const p = { scope: 'customer:read customer:write' };
        expect(scopes(p)).toEqual(['customer:read', 'customer:write']);
        expect(hasScope(p, 'customer:write')).toBe(true);
        expect(hasScope(p, 'admin')).toBe(false);
    });
});
