# Dynamic-Linking Hash — Canonical Specification

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §4.3 and `gui/use_cases/UC-G51_dynamic_linking_sca.md`.*
*Authority: this document. Other documents (UC-G05, ARCH §4.3, any future UC) must cite this form, not redefine it.*
*Phase 1 finding reference: FC.1.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Purpose

PSD2 RTS Art. 5 requires the SCA proof to be cryptographically bound to the transaction the customer believes they are authorising. The GUI, identity service, and resource servers (A4, A5, A1) must all compute the same hash over the same inputs in the same encoding, or A4 will reject legitimate payments (false negatives) or — worse — accept payments whose fields have been tampered with after SCA (false positives). This document fixes the canonical input set, encoding, and hash algorithm so that no component has interpretive room.

## Algorithm

```
dl_hash = lowercase(hex(SHA-256(canonical-JSON(inputs))))
```

- **Hash function:** SHA-256 (FIPS 180-4). 256-bit output.
- **Output encoding:** lowercase hex, 64 characters.
- **Input encoding:** canonical JSON per **RFC 8785 JSON Canonicalization Scheme (JCS)**. No exceptions.

### Why RFC 8785

RFC 8785 gives us:
- deterministic member ordering (lexicographic over UTF-16 code units),
- deterministic number representation (ECMAScript `Number.prototype.toString()` semantics, but we never pass Numbers — amounts are Strings, see below),
- normalized UTF-8 encoding,
- no whitespace.

Two compliant canonicalisers on different platforms produce byte-identical output. That is the property we require.

## Input-class schemas

`dl_hash` is computed over a JSON **object** whose shape depends on the operation class. The `class` field is always present and is the discriminator. The allowed classes are:

### Class: `payment`

Used by: UC-G05 (instant), UC-G06 (batch), UC-G07 (scheduled), UC-G11 (R2P accept), UC-G13 (R2P defer schedule), UC-G15 (QR-initiated).

```json
{
  "class": "payment",
  "paymentId": "<uuid>",
  "amount": "<decimal-string>",
  "currency": "<ISO-4217-3>",
  "creditorIban": "<IBAN-no-spaces>",
  "creditorName": "<NFC-UTF-8-string-max-140>",
  "executionMode": "INSTANT|STANDARD"
}
```

### Class: `payment-schedule`

Used by: UC-G07 (schedule creation).

```json
{
  "class": "payment-schedule",
  "scheduleId": "<uuid>",
  "amount": "<decimal-string>",
  "currency": "<ISO-4217-3>",
  "creditorIban": "<IBAN-no-spaces>",
  "creditorName": "<NFC-UTF-8-string-max-140>",
  "executeAt": "<ISO-8601-Z>",
  "executionMode": "INSTANT|STANDARD"
}
```

### Class: `mandate`

Used by: UC-G08, UC-G09 (modify).

```json
{
  "class": "mandate",
  "mandateId": "<uuid>",
  "merchantId": "<string>",
  "maxAmount": "<decimal-string>",
  "currency": "<ISO-4217-3>",
  "debtorAccountId": "<string>"
}
```

### Class: `vrp-consent`

Used by: UC-G10.

```json
{
  "class": "vrp-consent",
  "consentId": "<uuid>",
  "payee": "<creditor-name-NFC>",
  "payeeIban": "<IBAN-no-spaces>",
  "perPaymentMax": "<decimal-string>",
  "cumulativeCap": "<decimal-string>",
  "currency": "<ISO-4217-3>",
  "periodType": "DAILY|WEEKLY|MONTHLY|QUARTERLY",
  "frequencyMax": "<integer>",
  "validUntil": "<ISO-8601-Z>"
}
```

### Class: `psd2-consent`

Used by: UC-G17.

```json
{
  "class": "psd2-consent",
  "consentId": "<uuid>",
  "tppId": "<string>",
  "role": "AISP|PISP|CBPII",
  "scopes": ["<scope>", "…"],
  "accountIds": ["<accountId>", "…"],
  "validUntil": "<ISO-8601-Z>"
}
```

### Class: `beneficiary`

Used by: UC-G16 (add trusted beneficiary).

```json
{
  "class": "beneficiary",
  "beneficiaryId": "<uuid>",
  "name": "<NFC-UTF-8-string>",
  "iban": "<IBAN-no-spaces>"
}
```

### Class: `admin-action`

Used by: UC-G30 step-up SCA for `*:manage` actions; UC-G32 (fraud decision), UC-G33 (dismiss), UC-G34 (sanctions decision), UC-G35 (freeze/unfreeze/close), UC-G36 (PII reveal), UC-G37 (pacs.028), UC-G38 (return), UC-G39 (reconciliation run), UC-G40 (audit verify), UC-G41 (GDPR processing), UC-G42 (banner publish), UC-G43 (user admin).

```json
{
  "class": "admin-action",
  "action": "<action-string>",
  "resourceType": "case|hit|account|payment|audit|gdpr-request|user|banner",
  "resourceId": "<string>",
  "decision": "<optional-string>",
  "notesHash": "<lowercase-hex-SHA256-of-notes-in-NFC>"
}
```

The `action` enumerations are listed in `shared-schemas.yaml` under `AdminAction`. The `notesHash` binds the operator's notes into the proof without requiring the full notes text (which may exceed token size and contain PII we don't want in the JWT).

### Class: `gdpr-erasure`

Used by: UC-G25 (customer-initiated erasure).

```json
{
  "class": "gdpr-erasure",
  "customerId": "<string>",
  "accountIds": ["<accountId>", "…"]
}
```

### Class: `profile-change`

Used by: UC-G22 (address, email, phone changes).

```json
{
  "class": "profile-change",
  "field": "address|email|phone",
  "newValueHash": "<lowercase-hex-SHA256-of-newValue-in-NFC>"
}
```

## Field encoding rules (binding on every class)

1. **String fields are in Unicode Normalization Form C (NFC).** Before hashing, strings are normalised to NFC. This prevents hash drift from equivalent composed/decomposed forms (e.g., "é" U+00E9 vs "e" U+0065 + U+0301).

2. **Monetary amounts are strings, never JSON numbers.** JSON numbers have IEEE-754 semantics that may drift across JVM, V8, and Swift. Strings preserve exact representation.
   - Format: `^-?[0-9]+(\.[0-9]{1,4})?$`
   - No thousands separator.
   - Decimal separator is `.` (U+002E).
   - No trailing zeros beyond the currency's scale (e.g., EUR scale = 2 → `"1234.50"` not `"1234.5000"`; JPY scale = 0 → `"1234"` not `"1234.00"`).
   - Negative amounts are not used in dynamic linking (returns use a separate class; amount always positive here).

3. **IBAN is stored without spaces.** `NO9386011117947` not `NO93 8601 1117 947`. Mod-97 valid.

4. **Currency is ISO 4217 alpha-3, uppercase.** `NOK`, `EUR`, not `nok`.

5. **ISO 8601 timestamps use the `Z` suffix** (UTC) and millisecond precision where present: `2026-05-15T09:00:00.000Z`. Do not use timezone offsets (`+02:00`); convert to UTC before hashing.

6. **UUIDs are lowercase-hyphenated** (`550e8400-e29b-41d4-a716-446655440000`). Not braced, not uppercased.

7. **Arrays preserve meaningful order when the order is semantic** (e.g., `scopes`, `accountIds`). When order is not semantic but stability is required for the hash to be reproducible, sort lexicographically before hashing. This document decrees: `scopes` is lexicographic-sorted; `accountIds` is lexicographic-sorted.

8. **Unknown fields are rejected.** If the calling code produces a JSON object with a field not in the schema for its class, the identity service rejects the `POST /sca/start` with `invalid-input` and does not issue a challenge. This prevents future schema drift from creating hash-mismatch bugs post-deployment.

9. **Null fields are absent, not explicit.** A class's schema does not have nullable members; an optional field that is absent is simply not in the JSON object. Canonicalisation places no `"key": null` entries.

## Worked example — `payment` class

Inputs:
- paymentId: `550e8400-e29b-41d4-a716-446655440000`
- amount: `1234.56`
- currency: `NOK`
- creditorIban: `NO9386011117947`
- creditorName: `Ola Nordmann`
- executionMode: `INSTANT`

Canonical JSON (RFC 8785, lexicographic key order, UTF-8 NFC, no whitespace):

```
{"amount":"1234.56","class":"payment","creditorIban":"NO9386011117947","creditorName":"Ola Nordmann","currency":"NOK","executionMode":"INSTANT","paymentId":"550e8400-e29b-41d4-a716-446655440000"}
```

SHA-256 of these UTF-8 bytes: `<63 chars hex>... (computed by implementer; pinned in test fixture)`.

The exact hash value is not written here because a copy-paste could introduce a character drift. Instead, the implementation fixture in `implementation/api-specs/examples/dl-hash-fixture.json` holds the canonical inputs and the correct output; every platform's hash implementation is tested against that fixture.

## Test fixtures (Phase 2 artefact)

`implementation/api-specs/examples/dl-hash-fixtures/` will hold one JSON file per class with a concrete inputs object, the canonical JSON bytes (as base64 of the UTF-8 representation), and the expected hex hash. Each platform's library (TypeScript, Swift, Kotlin, Java) runs these fixtures as unit tests and fails the build on mismatch.

## Library choice

- **TypeScript (web):** `@lukeed/canonical` or an in-house JCS implementation; SHA-256 via `SubtleCrypto.digest('SHA-256')`.
- **Swift (iOS):** in-house RFC 8785 implementation (small); SHA-256 via `CryptoKit.SHA256`.
- **Kotlin (Android):** `io.github.erdtman.jcs` or in-house; SHA-256 via `java.security.MessageDigest`.
- **Java (backend, identity service + A4):** `io.github.erdtman:java-json-canonicalization`; SHA-256 via `MessageDigest.getInstance("SHA-256")`.

All four libraries are verified against the test fixtures in CI.

## Lifecycle

1. **GUI composes the inputs** (payment-form data or equivalent).
2. **GUI sends inputs to `POST /sca/start`.** Identity service recomputes `dl_hash` over the normalised inputs and returns `{scaSessionId, dl_hash, challenge}`.
3. **GUI recomputes `dl_hash` locally** from its version of the inputs and compares with the server's returned value. **Mismatch is fatal** (severe security event — UC-G51 AF-01).
4. **GUI renders the SCA screen** with the human-readable values from the inputs.
5. **Customer completes SCA.** Identity service mints an access-token increment (or a short-lived step-up token) carrying `dl_hash`.
6. **GUI calls the action endpoint** with the token. The resource server (A4 for payments; A5 for case decisions; etc.) recomputes `dl_hash` from the incoming request body and asserts equality with the token claim. **Mismatch → 403 `urn:problem:dynamic-linking-mismatch`** and the action is not performed.

## Error cases

| Condition | Response |
|-----------|----------|
| Class not in this document | `400 invalid-class` |
| Schema field missing | `400 missing-field/{field}` |
| Unknown field in inputs | `400 unknown-field/{field}` |
| Amount not matching format | `400 invalid-amount` |
| Currency not ISO 4217 | `400 invalid-currency` |
| IBAN fails mod-97 | `400 invalid-iban` |
| UTF-8 normalisation missing | recovered server-side (NFC-normalise before hashing); GUI expected to NFC-normalise to match |
| Local-vs-server hash mismatch | GUI aborts UC-G51 AF-01 |
| Token claim hash vs resource-call inputs mismatch | `403 urn:problem:dynamic-linking-mismatch` |

## Upgrade path

When we need to add a field (e.g., `executionDate` for future-dated instant payments), we **add a new class version**, e.g. `class: "payment.v2"`. The old `class: "payment"` remains supported during the transition. Resource servers accept both. GUIs are upgraded to emit the new class. After all GUIs are upgraded, the old class is removed. This prevents hash-contract breakage under partial deployments.

## Cross-references

- UC-G51 §2, §6 steps 2–10 — the canonical flow; refers to this document for the hash.
- UC-G05 §6 step 5 — payment-class example; refers to this document.
- ARCH §4.3 — diagram; states "hash per DYNAMIC_LINKING.md".
- `implementation/api-specs/shared-schemas.yaml` (Phase 2) — JSON Schema mirrors of each class.
- `implementation/api-specs/examples/dl-hash-fixtures/` (Phase 2) — test vectors.
