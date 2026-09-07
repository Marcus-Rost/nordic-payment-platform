rootProject.name = "jrpay"

include(
    // Shared libraries
    "jrpay-domain-model",
    "jrpay-security-starter",
    "jrpay-security-starter-kafka",

    // Services (A1-A6)
    "jrpay-account-ledger",         // A1: Event-Sourced Account Ledger
    "jrpay-vop-service",            // A6: Verification of Payee Service
    "jrpay-fraud-detection",        // A5: Real-time Fraud Detection Engine
    "jrpay-clearing-engine",        // A2: Payment Clearing & Settlement Engine
    "jrpay-bill-payment",           // A3: Bill Payment & Request-to-Pay Service
    "jrpay-psd2-api",               // A4: PSD2 Open Banking API Gateway

    // Identity (OAuth2 + SCA + password-reset + privacy notice + sessions)
    "jrpay-identity-service",

    // Notifications (inbox + SSE + preferences + Kafka projection + adapters)
    "jrpay-notifications",

    // Audit query (audit.events Kafka projection + search + chain verify + export)
    "jrpay-audit-query",

    // Smoke tests (cross-service integration against running services)
    "jrpay-smoke-tests"
)
