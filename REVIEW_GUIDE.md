# Review guide

This project provides evidence of production-oriented engineering, security ownership, and critical review of AI-assisted implementation in a high-integrity domain.

## A 15-minute review path

1. Read the root [README](README.md).
2. Review the [captured payment demonstration](demo/DEMO_OUTPUT.txt) and its [script](implementation/scripts/run-demo.sh).
3. Inspect the [account ledger](implementation/jrpay-account-ledger/src/main/java/) and [Verification of Payee](implementation/jrpay-vop-service/src/main/java/) implementations.
4. Read [AI-assisted development notes](docs/AI_ASSISTED_DEVELOPMENT.md) and the [end-to-end report](docs/E2E_REPORT.md).
5. Explore `reviews/` for security and implementation findings rather than relying only on the project's claims.

## Relevant signals

- AI-generated work treated as untrusted until reviewed and tested
- Financial invariants expressed in code and executable checks
- Security and privacy behavior visible in the demonstration
- Traceability across requirements, architecture, services, clients, and tests
- Failure recovery, idempotency, reconciliation, and auditability as first-class concerns

This is a portfolio reference implementation. It has never served real customers or processed real payments; all data and credentials are synthetic.
