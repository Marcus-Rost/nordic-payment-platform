// Per-run unique identity helpers. Per E2E_TESTING_GUIDE.md §4.8: tests that
// share a fixed identity become coupled to execution order and can produce
// false-greens against fixture fallbacks. Use freshXxx() per spec.

import { randomBytes } from 'node:crypto';

const stamp = (): string =>
    `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;

/** A 36-char ULID-shaped UUID. */
export const freshUuid = (): string =>
    [8, 4, 4, 4, 12]
        .map((n) => randomBytes(Math.ceil(n / 2)).toString('hex').slice(0, n))
        .join('-');

/** A short stamp suitable for embedding in human-readable test data. */
export const freshTag = (): string => `e2e-${stamp()}`;

/** A Norwegian-shaped IBAN in the test range. NOT a real bank code; for E2E only. */
export const freshTestIban = (): string => {
    // NO + 2 check digits + 4-digit bank code + 7-digit account number
    const bank = '8601'; // synthetic test bank code
    const acct = randomBytes(4).toString('hex').replace(/[a-f]/gi, (c) =>
        ((c.charCodeAt(0) - 87) % 10).toString(),
    ).slice(0, 7);
    // Check-digit not computed; identity-only marker for tests, not an
    // authoritative IBAN. Backend treats this as a synthetic demo value.
    return `NO93${bank}${acct}`;
};
