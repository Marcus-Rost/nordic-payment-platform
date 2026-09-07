# Shared Enumerations (GUI ↔ Backend)

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §8.1 (`shared-schemas.yaml`).*
*Authority: this document enumerates every GUI-surface enum; `shared-schemas.yaml` mirrors these in OpenAPI 3.1.*
*Phase 1 finding reference: FI.8, FC.7.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Overall discipline

- All enums are **string** in OpenAPI (not integer).
- Values are `UPPER_SNAKE_CASE` for status-like enums, `lowerCamelCase` for policy/role-like enums. Convention per-enum below.
- Unknown values on the GUI side: the typed client's TS/Swift/Kotlin binding accepts the value as `string` (not a closed enum on the wire) but the GUI code patterns-matches known values and falls back to a generic render. Forward-compat.
- Adding a value: producer side adds first, waits for consumer side to recognise, then emits. Removing a value: mark deprecated one minor release before removal.

---

## Payment enums

### PaymentStatus

Inherits from backend `jrpay-domain-model` + additions for GUI-visible states.

```
RECEIVED
AWAITING_RESERVATION
AWAITING_FRAUD
AWAITING_SANCTIONS
AWAITING_CLEARING
HELD                   // A5 fraud REVIEW decision
SETTLING
SETTLED
REJECTED
RETURNED               // pacs.004
CANCELLED              // customer-cancelled before submit; schedule cancelled before execution
PENDING_SCA            // intermediate; rarely visible
PENDING_EXECUTION      // scheduled payment awaiting date
```

### CustomerVisibleRejectionReason (FG.4 collapsed set)

Customer-facing; the internal reason code is broader. API collapses on output.

```
GENERIC_DECLINE
INSUFFICIENT_FUNDS
ACCOUNT_FROZEN
GENERIC_REGULATORY     // covers SANCTIONS_HIT + FRAUD_CONFIRMED — anti-tipping-off (FG.4)
NETWORK_TIMEOUT
AUTHORIZATION_FAILED
```

### InternalRejectionReason (admin-visible only)

For admin UIs (UC-G31..G43) the specific reason is visible with appropriate scope. Never exposed on customer endpoints.

```
INSUFFICIENT_FUNDS
ACCOUNT_FROZEN
ACCOUNT_CLOSED
SANCTIONS_HIT
FRAUD_CONFIRMED
DUPLICATE_DETECTED
CYCLE_REJECT           // NICS-side reject
RETURNED_BY_RECIPIENT
SCHEME_REJECT          // upstream scheme error
ACCOUNT_UNAVAILABLE    // transient lookup failure
CUSTOMER_REQUEST       // for returns
```

### ExecutionMode

```
INSTANT
STANDARD
BOOK_TRANSFER          // internal same-bank transfer; auto-detected per UC-G06 AF-04
```

## R2P enums (FC.7 resolution)

### R2pStatus

```
PENDING
ACCEPTED
DECLINED
DEFERRED                // UC-G13
DEFER_FAILED            // UC-G13 AF-02
AMENDMENT_REQUESTED     // UC-G14
REPLACED                // UC-G14 happy path (merchant sent new R2P)
CANCELLED_BY_MERCHANT
EXPIRED
```

### R2pDeclineReason (UC-G12)

```
ALREADY_PAID
WRONG_AMOUNT
INVOICE_INCORRECT
OTHER                   // requires free-text note ≥ 10 chars
```

## VoP enums

### VoPResult (from A6)

```
MATCH
CLOSE_MATCH
NO_MATCH
NOT_FOUND
TIMEOUT
ERROR
```

## Fraud and sanctions

### FraudDecision

```
OPEN
ASSIGNED
INVESTIGATING
CONFIRMED_FRAUD
DISMISSED
```

### SanctionsHitDecision (UC-G34)

```
OPEN
CONFIRMED_SANCTION
FALSE_POSITIVE
RELEASE_FOR_FRAUD_REVIEW
```

### SanctionsResult (from A5 automated scoring)

```
PASS
HIT
UNCERTAIN               // edge-of-threshold
```

## Consent enums

### ConsentStatus (applies to PSD2 and VRP)

```
PENDING_SCA
ACTIVE
EXPIRED
REVOKED
```

### PsdConsentRole

```
AISP
PISP
CBPII
```

### ConsentRevokeReason (UC-G18)

```
NO_LONGER_NEEDED
SECURITY_CONCERN
WRONG_PROVIDER
OTHER
```

## Mandate enums

### MandateStatus (UC-G08, UC-G09)

```
PENDING_SCA
ACTIVE
PAUSED
REVOKED
EXPIRED
```

### MandateFrequency

```
MONTHLY
QUARTERLY
ON_INVOICE
```

## Accounts

### AccountStatus

```
ACTIVE
FROZEN
CLOSED
```

### AccountType

```
CHECKING
SAVINGS
CREDIT
```

### FreezeReason (UC-G35)

```
FRAUD_INVESTIGATION
SANCTIONS_HOLD
REGULATORY_HOLD
OTHER
```

### UnfreezeReason (UC-G35)

```
INVESTIGATION_CONCLUDED
FALSE_POSITIVE_CONFIRMED
CUSTOMER_DISPUTE_RESOLVED
OTHER
```

## Admin and operators

### OperatorRole (D2 bundle)

```
FRAUD_ANALYST
COMPLIANCE_OFFICER
OPERATIONS
SUPERVISOR
ADMIN
```

### OperatorStatus (UC-G43)

```
PENDING_FIRST_LOGIN
ACTIVE
SUSPENDED
LOCKED
DELETED
```

### AdminAction (used in `admin-action` dynamic-linking class)

```
DECIDE_FRAUD
DECIDE_SANCTIONS
FREEZE_ACCOUNT
UNFREEZE_ACCOUNT
CLOSE_ACCOUNT
CREATE_ACCOUNT
REVEAL_PII
ISSUE_PACS028
RETURN_PAYMENT
RESOLVE_PAYMENT
RUN_RECONCILIATION
RESOLVE_DISCREPANCY
VERIFY_AUDIT_CHAIN
PROCESS_GDPR
REJECT_GDPR
PUBLISH_STATUS_BANNER
CREATE_OPERATOR
UPDATE_OPERATOR_ROLE
SUSPEND_OPERATOR
REACTIVATE_OPERATOR
DELETE_OPERATOR
UNLOCK_OPERATOR
```

### GdprRequestType

```
ACCESS
ERASURE
RECTIFICATION              // covered by UC-G22 directly; enum included for completeness
OBJECTION                  // out of MVP scope
```

### GdprRequestStatus

```
PENDING
IN_PROGRESS
IN_PROGRESS_RETRYING
READY                      // export artefact available
COMPLETED
REJECTED
FAILED
CANCELLED
EXPIRED
```

## Notifications

### NotificationCategory

```
PAYMENT_STATUS
FRAUD_HOLD
SANCTIONS_BLOCK
R2P_INCOMING
CONSENT_EVENTS
MANDATE_PRE_DEBIT
SECURITY_EVENTS
GDPR_STATUS
ACCOUNT_FREEZE
SYSTEM_MAINTENANCE
PROFILE_CHANGE
MARKETING
```

### NotificationChannel

```
INBOX
EMAIL
SMS
PUSH
```

### ConsentLogCategory (UC-G26)

Customer-visible consent-log classification used by `customer-privacy.yaml`.

```
PSD2_AIS
PSD2_PIS
PSD2_CBPII
VRP
MARKETING
NOTIFICATION_PREF
COOKIE
PRIVACY_NOTICE
GDPR_DISCLOSURE
```

### ConsentLogType (UC-G26)

The event type recorded against a category.

```
CONSENT_GRANTED
CONSENT_REVOKED
CONSENT_EXPIRED
CONSENT_RENEWED
MARKETING_OPTED_IN
MARKETING_OPTED_OUT
NOTIFICATION_PREF_CHANGED
COOKIE_CONSENT_UPDATED
PRIVACY_NOTICE_ACCEPTED
GDPR_DISCLOSURE_ACKNOWLEDGED
```

## Audit

### AuditSeverity

```
INFO
WARN
ERROR
CRITICAL
```

## Status banner (UC-G54)

### BannerSeverity

```
INFO
WARNING
CRITICAL
```

### BannerAudience

```
CUSTOMER
ADMIN
BOTH
```

## SCA

### ScaFactorType

```
TOTP
BANKID_SIMULATED
BANKID_REAL
BIOMETRIC                  // mobile, app-bound — not an SCA factor in the PSD2 sense on its own; used for refresh-token release
```

### ScaAcrLevel

```
loa1                       // password only
loa2                       // reserved
loa3                       // SCA passed (two-factor)
loa3-admin                 // SCA passed, admin audience
```

## Regional

### Currency

ISO 4217 alpha-3 codes restricted to the supported set:

```
NOK
EUR
SEK
DKK
USD
```

Others cause `urn:problem:currency-unsupported`.

### Locale

```
nb-NO
en-US
```

Per `PROJECT_DECISIONS.md` D1 + `gui_requirements.md` §2.4 i18n. Future Nynorsk (`nn-NO`) or Sami (`se-NO`) would be additive.

## Maintenance

- Every `shared-schemas.yaml` enum has a `description` that quotes the relevant paragraph of this document.
- A CI lint `jrpay-enums-match-shared` diffs the OpenAPI enum set against this markdown and fails on drift.
- New enum value: add here first; then spec file; then backend implementation; then GUI consumers.
