// SCA orchestration against /sca/start + /sca/{sessionId}/verify.
// Mirrors the admin-web implementation. The customer surface uses BankID
// as the primary factor; identity returns a TOTP simulation in dev.

import { api } from '../api/client';

export type ScaInputClass = 'payment-initiation' | 'mandate-action' | 'consent-grant' | 'gdpr-action';

export interface ScaInputs {
    class: ScaInputClass;
    [extension: string]: unknown;
}

export interface ScaStartResponse {
    scaSessionId: string;
    dl_hash: string;
    challenge: { type: string; metadata: Record<string, unknown> };
    exempt?: boolean;
    stepUpToken?: string;
}

export interface ScaVerifyResponse {
    status: 'VERIFIED';
    stepUpToken: string;
}

export async function startSca(
    inputs: ScaInputs,
    factorHint?: string
): Promise<ScaStartResponse> {
    return api<ScaStartResponse>('/sca/start', {
        method: 'POST',
        body: { inputs, factorHint },
    });
}

export async function verifySca(
    sessionId: string,
    proof:
        | { type: 'TOTP'; code: string }
        | { type: 'BANKID_SIMULATED'; assertion: string }
): Promise<ScaVerifyResponse> {
    return api<ScaVerifyResponse>(`/sca/${encodeURIComponent(sessionId)}/verify`, {
        method: 'POST',
        body: { proof },
    });
}
