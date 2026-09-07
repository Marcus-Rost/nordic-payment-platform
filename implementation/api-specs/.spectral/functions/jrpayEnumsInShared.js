// Spectral custom function: jrpayEnumsInShared
//
// Invoked per inline enum array under `components.schemas.**.enum` (or a
// property's `enum` — anywhere under the document). The rule's `given`
// JSONPath scopes the match to enum arrays; this function flags
// **exact-duplicate** declarations of a canonical enum from
// `gui/architecture/SHARED_ENUMS.md` (and its mirror in
// `shared-schemas.yaml`).
//
// Rationale: narrower subsets of a canonical enum are legitimate (e.g.
// `MandateUpdateRequest.status` only exposes `ACTIVE|PAUSED`); full
// duplications are not — they drift and contradict the single-source rule.
//
// The check is self-contained; canonical enum value-sets are embedded so
// the rule runs without loading the SHARED_ENUMS.md file at lint time.

const CANONICAL_ENUMS = {
  PaymentStatus: [
    "RECEIVED",
    "AWAITING_RESERVATION",
    "AWAITING_FRAUD",
    "AWAITING_SANCTIONS",
    "AWAITING_CLEARING",
    "HELD",
    "SETTLING",
    "SETTLED",
    "REJECTED",
    "RETURNED",
    "CANCELLED",
    "PENDING_SCA",
    "PENDING_EXECUTION",
  ],
  CustomerVisibleRejectionReason: [
    "GENERIC_DECLINE",
    "INSUFFICIENT_FUNDS",
    "ACCOUNT_FROZEN",
    "GENERIC_REGULATORY",
    "NETWORK_TIMEOUT",
    "AUTHORIZATION_FAILED",
  ],
  InternalRejectionReason: [
    "INSUFFICIENT_FUNDS",
    "ACCOUNT_FROZEN",
    "ACCOUNT_CLOSED",
    "SANCTIONS_HIT",
    "FRAUD_CONFIRMED",
    "DUPLICATE_DETECTED",
    "CYCLE_REJECT",
    "RETURNED_BY_RECIPIENT",
    "SCHEME_REJECT",
    "ACCOUNT_UNAVAILABLE",
    "CUSTOMER_REQUEST",
  ],
  ExecutionMode: ["INSTANT", "STANDARD", "BOOK_TRANSFER"],
  R2pStatus: [
    "PENDING",
    "ACCEPTED",
    "DECLINED",
    "DEFERRED",
    "DEFER_FAILED",
    "AMENDMENT_REQUESTED",
    "REPLACED",
    "CANCELLED_BY_MERCHANT",
    "EXPIRED",
  ],
  R2pDeclineReason: ["ALREADY_PAID", "WRONG_AMOUNT", "INVOICE_INCORRECT", "OTHER"],
  VoPResult: ["MATCH", "CLOSE_MATCH", "NO_MATCH", "NOT_FOUND", "TIMEOUT", "ERROR"],
  FraudDecision: ["OPEN", "ASSIGNED", "INVESTIGATING", "CONFIRMED_FRAUD", "DISMISSED"],
  SanctionsHitDecision: [
    "OPEN",
    "CONFIRMED_SANCTION",
    "FALSE_POSITIVE",
    "RELEASE_FOR_FRAUD_REVIEW",
  ],
  SanctionsResult: ["PASS", "HIT", "UNCERTAIN"],
  ConsentStatus: ["PENDING_SCA", "ACTIVE", "EXPIRED", "REVOKED"],
  PsdConsentRole: ["AISP", "PISP", "CBPII"],
  ConsentRevokeReason: ["NO_LONGER_NEEDED", "SECURITY_CONCERN", "WRONG_PROVIDER", "OTHER"],
  MandateStatus: ["PENDING_SCA", "ACTIVE", "PAUSED", "REVOKED", "EXPIRED"],
  MandateFrequency: ["MONTHLY", "QUARTERLY", "ON_INVOICE"],
  AccountStatus: ["ACTIVE", "FROZEN", "CLOSED"],
  AccountType: ["CHECKING", "SAVINGS", "CREDIT"],
  FreezeReason: ["FRAUD_INVESTIGATION", "SANCTIONS_HOLD", "REGULATORY_HOLD", "OTHER"],
  UnfreezeReason: [
    "INVESTIGATION_CONCLUDED",
    "FALSE_POSITIVE_CONFIRMED",
    "CUSTOMER_DISPUTE_RESOLVED",
    "OTHER",
  ],
  OperatorRole: [
    "FRAUD_ANALYST",
    "COMPLIANCE_OFFICER",
    "OPERATIONS",
    "SUPERVISOR",
    "ADMIN",
  ],
  OperatorStatus: ["PENDING_FIRST_LOGIN", "ACTIVE", "SUSPENDED", "LOCKED", "DELETED"],
  AdminAction: [
    "DECIDE_FRAUD",
    "DECIDE_SANCTIONS",
    "FREEZE_ACCOUNT",
    "UNFREEZE_ACCOUNT",
    "CLOSE_ACCOUNT",
    "CREATE_ACCOUNT",
    "REVEAL_PII",
    "ISSUE_PACS028",
    "RETURN_PAYMENT",
    "RESOLVE_PAYMENT",
    "RUN_RECONCILIATION",
    "RESOLVE_DISCREPANCY",
    "VERIFY_AUDIT_CHAIN",
    "PROCESS_GDPR",
    "REJECT_GDPR",
    "PUBLISH_STATUS_BANNER",
    "CREATE_OPERATOR",
    "UPDATE_OPERATOR_ROLE",
    "SUSPEND_OPERATOR",
    "REACTIVATE_OPERATOR",
    "DELETE_OPERATOR",
    "UNLOCK_OPERATOR",
  ],
  GdprRequestType: ["ACCESS", "ERASURE", "RECTIFICATION", "OBJECTION"],
  GdprRequestStatus: [
    "PENDING",
    "IN_PROGRESS",
    "IN_PROGRESS_RETRYING",
    "READY",
    "COMPLETED",
    "REJECTED",
    "FAILED",
    "CANCELLED",
    "EXPIRED",
  ],
  NotificationCategory: [
    "PAYMENT_STATUS",
    "FRAUD_HOLD",
    "SANCTIONS_BLOCK",
    "R2P_INCOMING",
    "CONSENT_EVENTS",
    "MANDATE_PRE_DEBIT",
    "SECURITY_EVENTS",
    "GDPR_STATUS",
    "ACCOUNT_FREEZE",
    "SYSTEM_MAINTENANCE",
    "PROFILE_CHANGE",
    "MARKETING",
  ],
  NotificationChannel: ["INBOX", "EMAIL", "SMS", "PUSH"],
  AuditSeverity: ["INFO", "WARN", "ERROR", "CRITICAL"],
  BannerSeverity: ["INFO", "WARNING", "CRITICAL"],
  BannerAudience: ["CUSTOMER", "ADMIN", "BOTH"],
  ScaFactorType: ["TOTP", "BANKID_SIMULATED", "BANKID_REAL", "BIOMETRIC"],
  ScaAcrLevel: ["loa1", "loa3", "loa3-admin"],
  Currency: ["NOK", "EUR", "SEK", "DKK", "USD"],
  Locale: ["nb-NO", "en-US"],
  ConsentLogCategory: [
    "PSD2_AIS",
    "PSD2_PIS",
    "PSD2_CBPII",
    "VRP",
    "MARKETING",
    "NOTIFICATION_PREF",
    "COOKIE",
    "PRIVACY_NOTICE",
    "GDPR_DISCLOSURE",
  ],
  ConsentLogType: [
    "CONSENT_GRANTED",
    "CONSENT_REVOKED",
    "CONSENT_EXPIRED",
    "CONSENT_RENEWED",
    "MARKETING_OPTED_IN",
    "MARKETING_OPTED_OUT",
    "NOTIFICATION_PREF_CHANGED",
    "COOKIE_CONSENT_UPDATED",
    "PRIVACY_NOTICE_ACCEPTED",
    "GDPR_DISCLOSURE_ACKNOWLEDGED",
  ],
};

function setsEqual(a, b) {
  if (a.size !== b.size) return false;
  for (const v of a) {
    if (!b.has(v)) return false;
  }
  return true;
}

export default function jrpayEnumsInShared(targetVal, _options, context) {
  if (!Array.isArray(targetVal)) return;
  if (targetVal.length === 0) return;
  // With `resolved: false` on the rule, this only fires for literal
  // inline enums in the linted spec file — not for enums inlined by the
  // resolver from shared-schemas.yaml. Belt-and-braces: still skip when
  // the path itself points at a canonical declaration (the case inside
  // shared-schemas.yaml when it is the file being linted directly).
  const path = context && context.path;
  if (Array.isArray(path) && path.length >= 3) {
    const last = path[path.length - 1];
    const schemaName = path[path.length - 2];
    const parent = path[path.length - 3];
    if (
      last === "enum" &&
      parent === "schemas" &&
      typeof schemaName === "string" &&
      Object.prototype.hasOwnProperty.call(CANONICAL_ENUMS, schemaName)
    ) {
      return;
    }
  }
  const declared = new Set(targetVal.map((v) => String(v)));
  const matches = [];
  for (const [name, values] of Object.entries(CANONICAL_ENUMS)) {
    if (setsEqual(declared, new Set(values))) {
      matches.push(name);
    }
  }
  if (matches.length === 0) return;
  return [
    {
      message: `Inline enum duplicates canonical ${matches.join(" / ")} — use $ref: "shared-schemas.yaml#/components/schemas/${matches[0]}" instead`,
    },
  ];
}
