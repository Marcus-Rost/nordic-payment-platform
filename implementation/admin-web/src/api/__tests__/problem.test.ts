import { describe, expect, it } from 'vitest';
import { decodeProblem, ProblemError } from '../problem';

function makeResponse(body: unknown, status = 400): Response {
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/problem+json' },
    });
}

describe('decodeProblem', () => {
    it('parses an RFC 9457 envelope', async () => {
        const r = makeResponse(
            {
                type: 'urn:problem:step-up-required',
                title: 'Step-up SCA required',
                status: 401,
                detail: 'Missing Step-Up-Token header.',
            },
            401
        );
        const e = await decodeProblem(r);
        expect(e).toBeInstanceOf(ProblemError);
        expect(e.problem.status).toBe(401);
        expect(e.problem.title).toBe('Step-up SCA required');
    });

    it('synthesises an envelope when the body is not JSON', async () => {
        const r = makeResponse('plain text body', 500);
        const e = await decodeProblem(r);
        expect(e.problem.type).toBe('urn:problem:unexpected');
        expect(e.problem.status).toBe(500);
    });

    it('isStepUpRequired discriminates by type URN', () => {
        const e = new ProblemError(
            {
                type: 'urn:problem:step-up-required',
                title: 't',
                status: 401,
            },
            new Response()
        );
        expect(e.isStepUpRequired).toBe(true);
        expect(e.isDynamicLinkingMismatch).toBe(false);
    });

    it('flags dynamic-linking mismatch and case-already-decided helpers', () => {
        const dl = new ProblemError(
            { type: 'urn:problem:dynamic-linking-mismatch', title: 't', status: 403 },
            new Response()
        );
        const decided = new ProblemError(
            { type: 'urn:problem:case-already-decided', title: 't', status: 409 },
            new Response()
        );
        expect(dl.isDynamicLinkingMismatch).toBe(true);
        expect(decided.isCaseAlreadyDecided).toBe(true);
    });
});
