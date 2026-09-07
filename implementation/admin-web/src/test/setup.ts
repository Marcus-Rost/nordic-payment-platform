// Vitest setup for jsdom unit tests.
//
// Wires @testing-library/jest-dom matchers into vitest's `expect`, and stubs
// browser globals that jsdom does not implement out of the box (notably
// crypto.subtle for the dynamic-linking hash helpers).

import '@testing-library/jest-dom/vitest';
import { webcrypto } from 'node:crypto';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

if (!globalThis.crypto || !globalThis.crypto.subtle) {
    Object.defineProperty(globalThis, 'crypto', {
        configurable: true,
        value: webcrypto,
    });
}

afterEach(() => {
    cleanup();
});
