// RFC 9457 Problem Details + step-up SCA helpers.

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

    get isCaseAlreadyDecided(): boolean {
        return this.problem.type === 'urn:problem:case-already-decided';
    }

    get isHitAlreadyDecided(): boolean {
        return this.problem.type === 'urn:problem:hit-already-decided';
    }

    get isDuplicateSuspected(): boolean {
        return this.problem.type === 'urn:problem:duplicate-suspected';
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
