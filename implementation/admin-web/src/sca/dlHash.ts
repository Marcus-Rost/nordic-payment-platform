// Dynamic-linking hash — byte-identical to identity / A1 / A3 / A4 / A5.
// Spec: gui/architecture/DYNAMIC_LINKING.md.
//
// Algorithm: SHA-256 of canonical JSON (RFC 8785 subset:
//   - members lex-sorted by UTF-16 code units,
//   - strings UTF-8 NFC,
//   - no whitespace,
//   - arrays preserve insertion order,
//   - amounts MUST be strings (numbers are forbidden in our canonical input).
// Output: lowercase hex of the 32-byte digest.

export interface AdminActionInputs {
    action: string;
    resourceType: string;
    resourceId: string;
    decision?: string;
    notesHash: string; // sha256HexNfc(notes)
}

export async function sha256HexNfc(value: string): Promise<string> {
    const nfc = (value ?? '').normalize('NFC');
    const bytes = new TextEncoder().encode(nfc);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return bytesToHex(new Uint8Array(digest));
}

export async function dlHash(inputs: Record<string, unknown>): Promise<{ canonical: string; hex: string }> {
    const canonical = canonicalise(inputs);
    const bytes = new TextEncoder().encode(canonical);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return { canonical, hex: bytesToHex(new Uint8Array(digest)) };
}

export async function adminActionDlHash(inputs: AdminActionInputs): Promise<{ canonical: string; hex: string }> {
    const ordered: Record<string, unknown> = {
        class: 'admin-action',
        action: inputs.action,
        resourceType: inputs.resourceType,
        resourceId: inputs.resourceId,
    };
    if (inputs.decision != null) ordered.decision = inputs.decision;
    ordered.notesHash = inputs.notesHash;
    return dlHash(ordered);
}

function canonicalise(value: unknown): string {
    if (value === null || value === undefined) return 'null';
    if (typeof value === 'string') return canonicaliseString(value);
    if (typeof value === 'boolean') return value ? 'true' : 'false';
    if (typeof value === 'number') {
        // Numbers MUST be strings in canonical inputs per the spec; if one
        // slips through, render via toString — caller error.
        return Number.isFinite(value) ? String(value) : 'null';
    }
    if (Array.isArray(value)) {
        return '[' + value.map(canonicalise).join(',') + ']';
    }
    if (typeof value === 'object') {
        const obj = value as Record<string, unknown>;
        const keys = Object.keys(obj).sort(); // UTF-16 code-unit order
        return (
            '{' +
            keys
                .map((k) => `${canonicaliseString(k)}:${canonicalise(obj[k])}`)
                .join(',') +
            '}'
        );
    }
    return 'null';
}

function canonicaliseString(value: string): string {
    const nfc = value.normalize('NFC');
    let out = '"';
    for (let i = 0; i < nfc.length; i++) {
        const c = nfc.charCodeAt(i);
        const ch = nfc[i]!;
        if (ch === '"') out += '\\"';
        else if (ch === '\\') out += '\\\\';
        else if (c === 0x08) out += '\\b';
        else if (c === 0x0c) out += '\\f';
        else if (ch === '\n') out += '\\n';
        else if (ch === '\r') out += '\\r';
        else if (ch === '\t') out += '\\t';
        else if (c < 0x20) out += `\\u${c.toString(16).padStart(4, '0')}`;
        else out += ch;
    }
    out += '"';
    return out;
}

function bytesToHex(bytes: Uint8Array): string {
    let s = '';
    for (let i = 0; i < bytes.length; i++) {
        s += bytes[i]!.toString(16).padStart(2, '0');
    }
    return s;
}
