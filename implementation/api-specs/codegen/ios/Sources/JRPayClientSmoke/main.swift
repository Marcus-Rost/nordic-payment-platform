// Phase 2E iOS codegen smoke.
//
// Imports generated types from a handful of the largest JRPay specs
// and uses them in trivial expressions. `swift build` exercises every
// target via SPM's plugin, and this file additionally exercises the
// types' assignability + enum case names. If the generator produced
// malformed Swift, `swift build` would fail.

import Foundation

import JRPayIdentity
import JRPayCustomerAccounts
import JRPayCustomerPayments
import JRPayCustomerR2p
import JRPayAdminFraud
import JRPayAdminAccounts
import JRPayAdminAudit

@main
struct JRPayClientSmoke {
    static func main() {
        // identity.yaml: OIDC discovery response has a dictionary body —
        // confirm the runtime types import cleanly.
        _ = JRPayIdentity.Components.self
        _ = JRPayIdentity.Operations.self

        // customer-accounts.yaml: reference the Accounts enum as a
        // sanity check on generated `Components.Schemas.*` tree.
        let status: JRPayCustomerAccounts.Components.Schemas.AccountStatus = .active
        _ = status

        // customer-payments.yaml: reference the payment status enum.
        let paymentStatus: JRPayCustomerPayments.Components.Schemas.PaymentStatus = .settled
        _ = paymentStatus

        // customer-r2p.yaml: reference the R2P status enum.
        let r2pStatus: JRPayCustomerR2p.Components.Schemas.R2pStatus = .pending
        _ = r2pStatus

        // admin-fraud.yaml: the decide-request's decision value set.
        let decision: JRPayAdminFraud.Components.Schemas.FraudCaseDetail.DecisionPayload.DecisionPayload = .dismissed
        _ = decision

        // admin-accounts.yaml: reveal-pii ack field enum.
        let field: JRPayAdminAccounts.Components.Schemas.RevealPiiAck.FieldPayload = .holderName
        _ = field

        // admin-audit.yaml: severity enum present and importable.
        let severity: JRPayAdminAudit.Components.Schemas.AuditSeverity = .info
        _ = severity

        print("JRPay Swift codegen smoke OK")
    }
}
