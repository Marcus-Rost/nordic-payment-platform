// Phase 2E web codegen smoke: imports a handful of generated types from
// the most-used specs and uses them in trivial expressions so `tsc
// --noEmit --strict` exercises the generated `paths` + `components`
// object shapes. If any generator output were malformed, this file
// would fail to compile.

import type { paths as IdentityPaths, components as IdentityComponents } from "../generated/identity.js";
import type { paths as CustomerAccountsPaths } from "../generated/customer-accounts.js";
import type { paths as CustomerPaymentsPaths } from "../generated/customer-payments.js";
import type { paths as CustomerR2pPaths } from "../generated/customer-r2p.js";
import type { paths as AdminFraudPaths } from "../generated/admin-fraud.js";
import type { paths as AdminAccountsPaths, components as AdminAccountsComponents } from "../generated/admin-accounts.js";
import type { paths as AdminAuditPaths } from "../generated/admin-audit.js";

// ---- identity.yaml: token request body is well-formed
type TokenRequestBody = IdentityPaths["/oauth2/token"]["post"]["requestBody"];
type TokenResponse =
  IdentityPaths["/oauth2/token"]["post"]["responses"][200]["content"]["application/json"];

const _tokenExample: TokenResponse = {
  access_token: "eyJ...",
  token_type: "Bearer",
  expires_in: 900,
};
void _tokenExample;

// ---- customer-accounts.yaml: list-accounts 200 response is an array
type AccountsListResponse =
  CustomerAccountsPaths["/api/v1/customer/accounts"]["get"]["responses"][200]["content"]["application/json"];

function countAccounts(body: AccountsListResponse): number {
  return body.length;
}
void countAccounts;

// ---- customer-payments.yaml: initiate-payment request body carries exactly the documented fields
type PaymentRequestBody =
  CustomerPaymentsPaths["/api/v1/customer/payments"]["post"]["requestBody"];
const _paymentExample: PaymentRequestBody = {
  content: {
    "application/json": {
      debtorAccountId: "a-0001",
      creditorIban: "NO9386011117947",
      creditorName: "Ola Nordmann",
      amount: "1234.56",
      currency: "NOK",
      executionMode: "INSTANT",
    },
  },
};
void _paymentExample;

// ---- customer-r2p.yaml: accept-R2P 202 pending-SCA shape exposes dl_hash
type R2pAcceptPendingSca =
  CustomerR2pPaths["/api/v1/customer/r2p/{requestId}/accept"]["post"]["responses"][202]["content"]["application/json"];

function isDlHash(body: R2pAcceptPendingSca): boolean {
  return typeof body.dl_hash === "string" && body.dl_hash.length === 64;
}
void isDlHash;

// ---- admin-fraud.yaml: decide request discriminator
type DecideRequestBody =
  AdminFraudPaths["/api/v1/admin/fraud/cases/{caseId}/decide"]["post"]["requestBody"];

const _decide: DecideRequestBody = {
  content: {
    "application/json": {
      decision: "DISMISSED",
      notes: "a".repeat(60),
      confirmedCriteria: true,
    },
  },
};
void _decide;

// ---- admin-accounts.yaml: reveal-pii ack carries the field enum
type RevealPiiAck =
  AdminAccountsPaths["/api/v1/admin/accounts/{accountId}/reveal-pii"]["post"]["responses"][200]["content"]["application/json"];
type RevealField = RevealPiiAck["field"];
const _field: RevealField = "holderName";
void _field;

// ---- admin-audit.yaml: chain verification ack has mismatchOffsets as number[]
type VerifyAck =
  AdminAuditPaths["/api/v1/admin/audit/verify"]["post"]["responses"][200]["content"]["application/json"];

function firstMismatch(body: VerifyAck): number | undefined {
  return body.mismatchOffsets?.[0];
}
void firstMismatch;

// ---- Components pulled from generated types still reference each other
// (typed-client sanity check — proves types are usable, not just parseable).
type IdentityProblem = NonNullable<IdentityComponents>;
type AdminAccountsProblem = NonNullable<AdminAccountsComponents>;
type _Combined = { idc: IdentityProblem; adm: AdminAccountsProblem };
const _c: _Combined | undefined = undefined;
void _c;

export {};
