import { describe, expect, it } from 'vitest';
import {
    clearVerifier,
    newNonce,
    newPkceChallenge,
    newState,
    readVerifier,
    stashVerifier,
} from '../pkce';

describe('PKCE', () => {
    it('produces a verifier of allowed length and an S256 challenge', async () => {
        const { verifier, challenge } = await newPkceChallenge();
        // base64url(sha256(...)) is 43 chars; verifier from 32 random bytes
        // base64url-encoded is 43 chars also.
        expect(verifier.length).toBe(43);
        expect(verifier).toMatch(/^[A-Za-z0-9_-]+$/);
        expect(challenge).toMatch(/^[A-Za-z0-9_-]+$/);
        expect(challenge.length).toBe(43);
    });

    it('newState / newNonce return distinct base64url tokens', () => {
        const a = newState();
        const b = newNonce();
        expect(a).not.toBe(b);
        expect(a).toMatch(/^[A-Za-z0-9_-]+$/);
        expect(b).toMatch(/^[A-Za-z0-9_-]+$/);
    });

    it('round-trips the stash through sessionStorage', () => {
        stashVerifier('v', 's', 'n');
        expect(readVerifier()).toEqual({ verifier: 'v', state: 's', nonce: 'n' });
        clearVerifier();
        expect(readVerifier()).toBeNull();
    });
});
