// Verifies the dynamic-linking hash matches the canonical-JSON algorithm
// used by the Java services (identity, A1, A3, A4, A5).
// (DYNAMIC_LINKING.md §"admin-action".)

import { describe, expect, it } from 'vitest';
import { adminActionDlHash, dlHash, sha256HexNfc } from '../dlHash';

describe('dlHash canonicalisation', () => {
    it('sorts keys lexicographically (UTF-16 code-unit order)', async () => {
        const a = await dlHash({ b: '1', a: '2' });
        const b = await dlHash({ a: '2', b: '1' });
        expect(a.canonical).toBe('{"a":"2","b":"1"}');
        expect(a.hex).toBe(b.hex);
    });

    it('NFC-normalises strings before hashing', async () => {
        // composed = U+00E9 ("é"), decomposed = U+0065 + U+0301 — must hash same.
        const composed = await sha256HexNfc('café');
        const decomposed = await sha256HexNfc('café');
        expect(composed).toBe(decomposed);
    });

    it('produces lowercase 64-char hex digests', async () => {
        const { hex } = await dlHash({ x: 'y' });
        expect(hex).toMatch(/^[0-9a-f]{64}$/);
    });

    it('handles nested objects and arrays', async () => {
        const { canonical } = await dlHash({
            arr: ['z', 'a', 'b'],
            obj: { y: 'b', x: 'a' },
        });
        // Arrays preserve insertion order; objects sort by key.
        expect(canonical).toBe('{"arr":["z","a","b"],"obj":{"x":"a","y":"b"}}');
    });

    it('escapes quote, backslash and newline', async () => {
        const { canonical } = await dlHash({ k: 'a"b\\c\n' });
        expect(canonical).toBe('{"k":"a\\"b\\\\c\\n"}');
    });
});

describe('adminActionDlHash', () => {
    it('emits the canonical key order required by the server', async () => {
        const { canonical } = await adminActionDlHash({
            action: 'DECIDE_FRAUD',
            resourceType: 'case',
            resourceId: '11111111-1111-1111-1111-111111111111',
            decision: 'CONFIRMED_FRAUD',
            notesHash: 'a'.repeat(64),
        });
        // Sorted: action, class, decision, notesHash, resourceId, resourceType.
        expect(canonical).toBe(
            '{"action":"DECIDE_FRAUD",' +
                '"class":"admin-action",' +
                '"decision":"CONFIRMED_FRAUD",' +
                '"notesHash":"' +
                'a'.repeat(64) +
                '",' +
                '"resourceId":"11111111-1111-1111-1111-111111111111",' +
                '"resourceType":"case"}'
        );
    });

    it('omits decision when not provided', async () => {
        const { canonical } = await adminActionDlHash({
            action: 'FREEZE_ACCOUNT',
            resourceType: 'account',
            resourceId: 'NO9300000000000',
            notesHash: 'b'.repeat(64),
        });
        expect(canonical).not.toContain('decision');
    });
});
