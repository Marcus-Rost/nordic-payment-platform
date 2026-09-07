// Probes every required service before any spec runs. Stores the result
// in the file referenced by E2E_PREFLIGHT_FILE (default /tmp/jrpay-e2e-preflight.json)
// so individual specs can `test.skip()` cleanly when an endpoint is down,
// rather than failing red without a clear cause.
//
// Pattern from E2E_TESTING_GUIDE.md §3.1: "A test suite that's red because
// the stack isn't up trains people to ignore failures."

import { writeFileSync } from 'node:fs';
import { ENDPOINTS, REQUIRED_FOR_E2E, healthUrl, type EndpointKey } from './endpoints';

export const PREFLIGHT_FILE =
    process.env.E2E_PREFLIGHT_FILE ?? '/tmp/jrpay-e2e-preflight.json';

export interface PreflightResult {
    ok: boolean;
    capturedAt: string;
    services: Record<EndpointKey, { url: string; healthUrl: string; status: number | string; ok: boolean }>;
    missing: EndpointKey[];
}

async function probe(url: string): Promise<{ status: number | string; ok: boolean }> {
    try {
        const res = await fetch(url, {
            method: 'GET',
            signal: AbortSignal.timeout(5_000),
        });
        return { status: res.status, ok: res.ok };
    } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        return { status: msg, ok: false };
    }
}

export default async function globalSetup(): Promise<void> {
    const services = {} as PreflightResult['services'];
    const missing: EndpointKey[] = [];

    const keys = Object.keys(ENDPOINTS) as EndpointKey[];
    for (const key of keys) {
        const appUrl = ENDPOINTS[key];
        const probeUrl = healthUrl(key);
        const r = await probe(probeUrl);
        services[key] = { url: appUrl, healthUrl: probeUrl, ...r };
        if (REQUIRED_FOR_E2E.includes(key) && !r.ok) {
            missing.push(key);
        }
    }

    const result: PreflightResult = {
        ok: missing.length === 0,
        capturedAt: new Date().toISOString(),
        services,
        missing,
    };

    writeFileSync(PREFLIGHT_FILE, JSON.stringify(result, null, 2));

    // eslint-disable-next-line no-console
    console.log(
        `[globalSetup] ${result.ok ? 'OK' : 'PARTIAL'} — ${
            Object.values(services).filter((s) => s.ok).length
        }/${keys.length} services responding. Preflight file: ${PREFLIGHT_FILE}`
    );
    if (missing.length > 0) {
        // eslint-disable-next-line no-console
        console.log(`[globalSetup] Required services down: ${missing.join(', ')}`);
        // eslint-disable-next-line no-console
        console.log('[globalSetup] Specs that depend on these will skip with a clear message.');
    }
}
