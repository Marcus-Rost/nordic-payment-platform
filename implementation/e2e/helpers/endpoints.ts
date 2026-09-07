// Single source of truth for JRPay service endpoints. Pulled from
// implementation/docker/docker-compose.yml — keep in sync if compose changes.
//
// Each backend service exposes:
//   - app port  (REST API, e.g. 8091 for account-ledger)
//   - mgmt port (Spring Actuator, e.g. 9091 for account-ledger)
//
// /actuator/health lives on the mgmt port — the app port returns 500 with
// "No static resource actuator/health" because actuator's WebEndpoint is
// rebound to the management server. Use `healthUrl(...)` for liveness probes
// and the bare endpoint URL for REST calls.
//
// All values can be overridden by env vars to support running against a
// different stack (e.g. a remote dev environment).

const env = (k: string, fallback: string): string => process.env[k] ?? fallback;

interface BackendEndpoint {
    app: string;
    mgmt: string;
}

interface GuiEndpoint {
    app: string;
    health: string;
}

const backend = (appPort: number, mgmtPort: number, appKey: string, mgmtKey: string): BackendEndpoint => ({
    app: env(appKey, `http://localhost:${appPort}`),
    mgmt: env(mgmtKey, `http://localhost:${mgmtPort}`),
});

export const SERVICES = {
    identity: backend(8097, 9097, 'E2E_IDENTITY', 'E2E_IDENTITY_MGMT'),
    accountLedger: backend(8091, 9091, 'E2E_A1', 'E2E_A1_MGMT'),
    // clearing-engine compose ports are 8112/9112 but the app actually binds
    // 8092/9082 inside the container — known mis-wiring (OPEN-1). It's a
    // Kafka-only service so REST probing isn't load-bearing.
    clearingEngine: backend(8112, 9112, 'E2E_A2', 'E2E_A2_MGMT'),
    billPayment: backend(8093, 9093, 'E2E_A3', 'E2E_A3_MGMT'),
    psd2Api: backend(8094, 9094, 'E2E_A4', 'E2E_A4_MGMT'),
    fraudDetection: backend(8095, 9095, 'E2E_A5', 'E2E_A5_MGMT'),
    vop: backend(8096, 9096, 'E2E_A6', 'E2E_A6_MGMT'),
    notifications: backend(8098, 9098, 'E2E_NOTIF', 'E2E_NOTIF_MGMT'),
    auditQuery: backend(8099, 9099, 'E2E_AUDIT', 'E2E_AUDIT_MGMT'),
} as const;

export const GUI = {
    adminWeb: { app: env('E2E_ADMIN_WEB', 'http://localhost:5174'), health: '/healthz' } satisfies GuiEndpoint,
    clientWeb: { app: env('E2E_CLIENT_WEB', 'http://localhost:5173'), health: '/healthz' } satisfies GuiEndpoint,
} as const;

// Backwards-compatible flat view used by helpers that don't care which port is which.
export const ENDPOINTS = {
    adminWeb: GUI.adminWeb.app,
    clientWeb: GUI.clientWeb.app,
    identity: SERVICES.identity.app,
    accountLedger: SERVICES.accountLedger.app,
    clearingEngine: SERVICES.clearingEngine.app,
    billPayment: SERVICES.billPayment.app,
    psd2Api: SERVICES.psd2Api.app,
    fraudDetection: SERVICES.fraudDetection.app,
    vop: SERVICES.vop.app,
    notifications: SERVICES.notifications.app,
    auditQuery: SERVICES.auditQuery.app,
} as const;

export type EndpointKey = keyof typeof ENDPOINTS;

/** Returns the URL that should respond 200 with `{"status":"UP"}` for liveness probing. */
export function healthUrl(key: EndpointKey): string {
    if (key === 'adminWeb') return `${GUI.adminWeb.app}${GUI.adminWeb.health}`;
    if (key === 'clientWeb') return `${GUI.clientWeb.app}${GUI.clientWeb.health}`;
    // Backend services: actuator/health on the management port.
    const svc = SERVICES[key as keyof typeof SERVICES];
    return `${svc.mgmt}/actuator/health`;
}

// Services we require to be live for cross-client tests to be meaningful.
// Tests skip cleanly when one of these is down (see globalSetup).
//
// `clearingEngine` is intentionally omitted — its mgmt port mapping is
// mis-wired (OPEN-1). We exercise it indirectly via the Kafka payment chain.
export const REQUIRED_FOR_E2E: EndpointKey[] = [
    'adminWeb',
    'clientWeb',
    'identity',
    'accountLedger',
    'billPayment',
    'vop',
    'auditQuery',
    'psd2Api',
    'fraudDetection',
    'notifications',
];

// Demo credentials seeded by V003/V008 in identity. The bcrypt hash for these
// passwords was fixed by F3 (V008) in the prior session — if dev-token issuance
// fails with "Bad credentials", check that V008 ran by querying flyway_schema_history.
export const DEMO_USERS = {
    admin: {
        username: 'admin@jrpay.example',
        password: 'demo-password-1!',
        audience: 'admin' as const,
    },
    customer: {
        username: 'ola.nordmann@example.com',
        password: 'demo-password-1!',
        audience: 'customer' as const,
    },
} as const;
