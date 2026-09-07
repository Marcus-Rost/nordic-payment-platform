# Payment demonstration

This demonstration uses synthetic Norwegian names, account identifiers, balances, and credentials. It exercises real HTTP endpoints backed by the reference services and local PostgreSQL/Kafka infrastructure.

## Fast review

1. Read [the captured output](DEMO_OUTPUT.txt).
2. Review [the exact commands](DEMO_INSTRUCTIONS.md).
3. Inspect the executable [demo script](../implementation/scripts/run-demo.sh).
4. Follow the related source into the [account ledger](../implementation/jrpay-account-ledger/src/main/java/) and [Verification of Payee service](../implementation/jrpay-vop-service/src/main/java/).

## What it demonstrates

- Accounts and balances managed through authenticated endpoints
- Verification of Payee before payment
- No disclosure of the holder's actual name on a no-match response
- Atomic balance changes in the demonstrated transfer
- Conservation of total money before and after the transfer

The obvious local credentials are synthetic demo fixtures, not production secrets. This portfolio system has never processed real customer data or real payments.

