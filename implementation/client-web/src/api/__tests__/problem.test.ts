import { describe, expect, it } from 'vitest';
import { decodeProblem, ProblemError } from '../problem';

function makeResponse(body: unknown, status = 400): Response {
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/problem+json' },
    });
}

describe('decodeProblem', () => {
    it('parses RFC 9457 envelope', async () => {
        const r = makeResponse(
            {
                type: 'urn:problem:vop-mismatch',
                title: 'Payee mismatch',
                status: 422,
            },
            422
        );
        const e = await decodeProblem(r);
        expect(e).toBeInstanceOf(ProblemError);
        expect(e.problem.type).toBe('urn:problem:vop-mismatch');
    });

    it('synthesises envelope when body is not JSON', async () => {
        const r = makeResponse('plain', 503);
        const e = await decodeProblem(r);
        expect(e.problem.status).toBe(503);
        expect(e.problem.type).toBe('urn:problem:unexpected');
    });

    it('flag helpers cover customer-side problem types', () => {
        const make = (type: string, status = 400) =>
            new ProblemError({ type, title: 't', status }, new Response());
        expect(make('urn:problem:step-up-required', 401).isStepUpRequired).toBe(true);
        expect(make('urn:problem:dynamic-linking-mismatch', 403).isDynamicLinkingMismatch).toBe(
            true
        );
        expect(make('urn:problem:vop-mismatch').isVopMismatch).toBe(true);
        expect(make('urn:problem:vop-unavailable').isVopUnavailable).toBe(true);
        expect(make('urn:problem:fraud-hold').isFraudHold).toBe(true);
        expect(make('urn:problem:sanctions-block').isSanctionsBlock).toBe(true);
        expect(make('urn:problem:mandate-cap-exceeded').isMandateCapExceeded).toBe(true);
        expect(make('urn:problem:insufficient-funds').isInsufficientFunds).toBe(true);
        expect(make('urn:problem:account-frozen').isAccountFrozen).toBe(true);
    });
});
