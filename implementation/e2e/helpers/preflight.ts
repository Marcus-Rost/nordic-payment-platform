// Reads the preflight result written by globalSetup. Specs use this to
// skip cleanly when their required services are down, rather than failing
// red without a clear cause.

import { readFileSync } from 'node:fs';
import { test } from '@playwright/test';
import type { EndpointKey } from './endpoints';
import { PREFLIGHT_FILE, type PreflightResult } from './globalSetup';

let cached: PreflightResult | undefined;

export function loadPreflight(): PreflightResult {
    if (cached) return cached;
    const raw = readFileSync(PREFLIGHT_FILE, 'utf8');
    cached = JSON.parse(raw) as PreflightResult;
    return cached;
}

/**
 * Skip the current spec if any of the named services is not responding.
 * Use at the top of every spec that depends on backend services so a
 * stack-down state produces "skipped" rather than "failed".
 */
export function requireServices(...required: EndpointKey[]): void {
    const pf = loadPreflight();
    const down = required.filter((k) => !pf.services[k]?.ok);
    if (down.length > 0) {
        const reasons = down
            .map((k) => `${k}=${pf.services[k]?.status ?? 'missing'}`)
            .join(', ');
        test.skip(true, `[preflight] required services not responding: ${reasons}`);
    }
}
