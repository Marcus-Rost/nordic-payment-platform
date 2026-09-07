// Dynamic-linking hash — byte-identical to identity / A1 / A3 / A4 / A5.
// Spec: gui/architecture/DYNAMIC_LINKING.md.
//
// Customer surface uses two classes:
//   - `payment-initiation` for instant + scheduled payments.
//   - `mandate-action` for AvtaleGiro mandate setup / amend / terminate.
//   - `consent-grant` for PSD2 / VRP consent grants.
//   - `gdpr-action` for GDPR export / erasure.
//
// Algorithm: SHA-256 of canonical JSON (RFC 8785 subset:
//   - members lex-sorted by UTF-16 code units,
//   - strings UTF-8 NFC,
//   - no whitespace,
//   - arrays preserve insertion order,
//   - amounts MUST be strings).
// Output: lowercase hex of the 32-byte digest.

export interface PaymentInitiationInputs {
    creditorIban: string;
    debtorAccountId: string;
    amount: string;
    currency: string;
    reference?: string;
    executionDate?: string;
}

export interface MandateActionInputs {
    action: 'CREATE' | 'AMEND' | 'TERMINATE';
    creditorId: string;
    debtorAccountId: string;
    capAmount?: string;
    capCurrency?: string;
}

export interface ConsentGrantInputs {
    consentType: 'AISP' | 'PISP' | 'CBPII' | 'VRP';
    tppId: string;
    accountIds?: string[];
    expiresAt: string;
}

export interface GdprActionInputs {
    action: 'EXPORT' | 'ERASURE_REQUEST';
    customerId: string;
    notesHash: string;
}

export async function sha256HexNfc(value: string): Promise<string> {
    const nfc = (value ?? '').normalize('NFC');
    const bytes = new TextEncoder().encode(nfc);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return bytesToHex(new Uint8Array(digest));
}

export async function dlHash(
    inputs: Record<string, unknown>
): Promise<{ canonical: string; hex: string }> {
    const canonical = canonicalise(inputs);
    const bytes = new TextEncoder().encode(canonical);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return { canonical, hex: bytesToHex(new Uint8Array(digest)) };
}

export async function paymentInitiationDlHash(
    inputs: PaymentInitiationInputs
): Promise<{ canonical: string; hex: string }> {
    const ordered: Record<string, unknown> = {
        amount: inputs.amount,
        class: 'payment-initiation',
        creditorIban: inputs.creditorIban,
        currency: inputs.currency,
        debtorAccountId: inputs.debtorAccountId,
    };
    if (inputs.executionDate != null) ordered.executionDate = inputs.executionDate;
    if (inputs.reference != null) ordered.reference = inputs.reference;
    return dlHash(ordered);
}

export async function mandateActionDlHash(
    inputs: MandateActionInputs
): Promise<{ canonical: string; hex: string }> {
    const ordered: Record<string, unknown> = {
        action: inputs.action,
        class: 'mandate-action',
        creditorId: inputs.creditorId,
        debtorAccountId: inputs.debtorAccountId,
    };
    if (inputs.capAmount != null) ordered.capAmount = inputs.capAmount;
    if (inputs.capCurrency != null) ordered.capCurrency = inputs.capCurrency;
    return dlHash(ordered);
}

export async function consentGrantDlHash(
    inputs: ConsentGrantInputs
): Promise<{ canonical: string; hex: string }> {
    const ordered: Record<string, unknown> = {
        class: 'consent-grant',
        consentType: inputs.consentType,
        expiresAt: inputs.expiresAt,
        tppId: inputs.tppId,
    };
    if (inputs.accountIds && inputs.accountIds.length > 0) {
        ordered.accountIds = [...inputs.accountIds];
    }
    return dlHash(ordered);
}

export async function gdprActionDlHash(
    inputs: GdprActionInputs
): Promise<{ canonical: string; hex: string }> {
    return dlHash({
        action: inputs.action,
        class: 'gdpr-action',
        customerId: inputs.customerId,
        notesHash: inputs.notesHash,
    });
}

function canonicalise(value: unknown): string {
    if (value === null || value === undefined) return 'null';
    if (typeof value === 'string') return canonicaliseString(value);
    if (typeof value === 'boolean') return value ? 'true' : 'false';
    if (typeof value === 'number') {
        return Number.isFinite(value) ? String(value) : 'null';
    }
    if (Array.isArray(value)) {
        return '[' + value.map(canonicalise).join(',') + ']';
    }
    if (typeof value === 'object') {
        const obj = value as Record<string, unknown>;
        const keys = Object.keys(obj).sort();
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
