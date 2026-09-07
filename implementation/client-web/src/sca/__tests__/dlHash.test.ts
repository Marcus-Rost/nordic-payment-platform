// Verifies dynamic-linking hashes match the canonical-JSON algorithm used by
// the Java services and admin-web. Any drift here would fail server-side
// SCA validation with urn:problem:dynamic-linking-mismatch.

import { describe, expect, it } from 'vitest';
import {
    consentGrantDlHash,
    dlHash,
    gdprActionDlHash,
    mandateActionDlHash,
    paymentInitiationDlHash,
    sha256HexNfc,
} from '../dlHash';

describe('canonicalisation', () => {
    it('sorts keys lexicographically', async () => {
        const a = await dlHash({ b: 'x', a: 'y' });
        const b = await dlHash({ a: 'y', b: 'x' });
        expect(a.canonical).toBe('{"a":"y","b":"x"}');
        expect(a.hex).toBe(b.hex);
    });

    it('NFC-normalises strings', async () => {
        const composed = 'caf' + String.fromCharCode(0x00e9);
        const decomposed = 'cafe' + String.fromCharCode(0x0301);
        expect(composed).not.toBe(decomposed);
        expect(await sha256HexNfc(composed)).toBe(await sha256HexNfc(decomposed));
    });

    it('produces lowercase 64-char hex', async () => {
        const { hex } = await dlHash({ x: 'y' });
        expect(hex).toMatch(/^[0-9a-f]{64}$/);
    });

    it('escapes quote, backslash, newline', async () => {
        const { canonical } = await dlHash({ k: 'a"b\\c\n' });
        expect(canonical).toBe('{"k":"a\\"b\\\\c\\n"}');
    });
});

describe('paymentInitiationDlHash', () => {
    it('produces canonical key order required by the server', async () => {
        const { canonical } = await paymentInitiationDlHash({
            creditorIban: 'NO9300000000000',
            debtorAccountId: 'acc-1',
            amount: '100.00',
            currency: 'NOK',
            reference: 'INVOICE-1',
        });
        expect(canonical).toBe(
            '{"amount":"100.00",' +
                '"class":"payment-initiation",' +
                '"creditorIban":"NO9300000000000",' +
                '"currency":"NOK",' +
                '"debtorAccountId":"acc-1",' +
                '"reference":"INVOICE-1"}'
        );
    });

    it('omits optional reference when absent', async () => {
        const { canonical } = await paymentInitiationDlHash({
            creditorIban: 'NO9300000000000',
            debtorAccountId: 'acc-1',
            amount: '100.00',
            currency: 'NOK',
        });
        expect(canonical).not.toContain('reference');
        expect(canonical).not.toContain('executionDate');
    });
});

describe('mandateActionDlHash', () => {
    it('emits canonical key order for CREATE', async () => {
        const { canonical } = await mandateActionDlHash({
            action: 'CREATE',
            creditorId: 'creditor-1',
            debtorAccountId: 'acc-1',
            capAmount: '500.00',
            capCurrency: 'NOK',
        });
        expect(canonical).toBe(
            '{"action":"CREATE",' +
                '"capAmount":"500.00",' +
                '"capCurrency":"NOK",' +
                '"class":"mandate-action",' +
                '"creditorId":"creditor-1",' +
                '"debtorAccountId":"acc-1"}'
        );
    });

    it('omits cap fields for TERMINATE', async () => {
        const { canonical } = await mandateActionDlHash({
            action: 'TERMINATE',
            creditorId: 'creditor-1',
            debtorAccountId: 'acc-1',
        });
        expect(canonical).not.toContain('capAmount');
        expect(canonical).not.toContain('capCurrency');
    });
});

describe('consentGrantDlHash', () => {
    it('preserves accountIds insertion order while sorting top-level keys', async () => {
        const { canonical } = await consentGrantDlHash({
            consentType: 'AISP',
            tppId: 'tpp-1',
            accountIds: ['acc-z', 'acc-a'],
            expiresAt: '2026-12-31T00:00:00Z',
        });
        expect(canonical).toContain('"accountIds":["acc-z","acc-a"]');
        expect(canonical).toMatch(/^\{"accountIds":/);
    });
});

describe('gdprActionDlHash', () => {
    it('hashes the four required keys', async () => {
        const { canonical, hex } = await gdprActionDlHash({
            action: 'EXPORT',
            customerId: 'cust-1',
            notesHash: 'a'.repeat(64),
        });
        expect(canonical).toBe(
            '{"action":"EXPORT",' +
                '"class":"gdpr-action",' +
                '"customerId":"cust-1",' +
                '"notesHash":"' + 'a'.repeat(64) + '"}'
        );
        expect(hex).toMatch(/^[0-9a-f]{64}$/);
    });
});
