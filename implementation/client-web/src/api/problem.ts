// RFC 9457 Problem Details + step-up SCA helpers (customer surface).
//
// The customer side hits a slightly different problem-type catalogue than the
// admin side — VoP mismatch, fraud hold, sanctions block, mandate cap exceeded
// etc. The base shape is identical so callers use the same `ProblemError`.

export interface ProblemDetail {
    type: string;
    title: string;
    status: number;
    detail?: string;
    instance?: string;
    [extension: string]: unknown;
}

export class ProblemError extends Error {
    readonly problem: ProblemDetail;
    readonly response: Response;

    constructor(problem: ProblemDetail, response: Response) {
        super(`${problem.type}: ${problem.title}${problem.detail ? ` — ${problem.detail}` : ''}`);
        this.problem = problem;
        this.response = response;
    }

    get type(): string {
        return this.problem.type;
    }

    get isStepUpRequired(): boolean {
        return this.problem.type === 'urn:problem:step-up-required';
    }

    get isDynamicLinkingMismatch(): boolean {
        return this.problem.type === 'urn:problem:dynamic-linking-mismatch';
    }

    get isVopMismatch(): boolean {
        return this.problem.type === 'urn:problem:vop-mismatch';
    }

    get isVopUnavailable(): boolean {
        return this.problem.type === 'urn:problem:vop-unavailable';
    }

    get isFraudHold(): boolean {
        return this.problem.type === 'urn:problem:fraud-hold';
    }

    get isSanctionsBlock(): boolean {
        return this.problem.type === 'urn:problem:sanctions-block';
    }

    get isMandateCapExceeded(): boolean {
        return this.problem.type === 'urn:problem:mandate-cap-exceeded';
    }

    get isInsufficientFunds(): boolean {
        return this.problem.type === 'urn:problem:insufficient-funds';
    }

    get isAccountFrozen(): boolean {
        return this.problem.type === 'urn:problem:account-frozen';
    }
}

export async function decodeProblem(response: Response): Promise<ProblemError> {
    let body: unknown;
    try {
        body = await response.json();
    } catch {
        body = null;
    }
    const problem: ProblemDetail =
        body && typeof body === 'object' && 'type' in (body as Record<string, unknown>)
            ? (body as ProblemDetail)
            : {
                  type: 'urn:problem:unexpected',
                  title: response.statusText || 'Unexpected error',
                  status: response.status,
                  detail: typeof body === 'string' ? body : undefined,
              };
    return new ProblemError(problem, response);
}
