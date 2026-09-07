// SCA orchestration against /sca/start + /sca/{sessionId}/verify.
//
// Caller flow (UC-G51):
//   const inputs = adminActionInputs({ action, resourceType, resourceId, decision, notes });
//   const result = await runSca(inputs);
//   // result.stepUpToken is the Step-Up-Token header for the mutating call.

import { api } from '../api/client';

export interface AdminActionScaInput {
    class: 'admin-action';
    action: string;
    resourceType: string;
    resourceId: string;
    decision?: string;
    notesHash: string;
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

export async function startSca(inputs: AdminActionScaInput, factorHint?: string): Promise<ScaStartResponse> {
    return api<ScaStartResponse>('/sca/start', {
        method: 'POST',
        body: { inputs, factorHint },
    });
}

export async function verifySca(
    sessionId: string,
    proof: { type: 'TOTP'; code: string } | { type: 'BANKID_SIMULATED'; assertion: string }
): Promise<ScaVerifyResponse> {
    return api<ScaVerifyResponse>(`/sca/${encodeURIComponent(sessionId)}/verify`, {
        method: 'POST',
        body: { proof },
    });
}
