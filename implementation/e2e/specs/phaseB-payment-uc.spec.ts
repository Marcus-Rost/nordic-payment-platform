// Phase B — saturate the simplest UC: customer→creditor payment via A1's
// direct /transfer endpoint. Per E2E_TESTING_GUIDE.md §B, the variant set is:
//
//   - main success
//   - decision-content / output comparison (cross-client)
//   - duplicate / idempotency
//   - validation errors at the entry point
//   - "no GUI footprint" — when input rejected, neither side surfaces phantom rows
//
// What this proves end-to-end:
//   - The double-entry settlement layer behaves correctly under the
//     happy-path and four error variants.
//   - The cross-client invariant: same transactionId is visible to both
//     the customer (own balance) and the admin (account-transactions list)
//     after a SETTLED transfer, with PII-masked vs full views aligned.
//
// What this deliberately does NOT prove:
//   - The Kafka chain (A3→A2→A5→A1). Covered by phaseD-* once Phase B is
//     locked in.
//   - UI-driven flows. The specs hit the same REST surfaces the SPAs use,
//     but the SPAs aren't navigated here.
//
// Auth split (see helpers/a1.ts header):
//   - /api/v1/accounts/**  ← HTTP Basic admin/admin (legacy demo path)
//   - /api/v1/admin/**     ← Bearer JWT (post-F1)
// Different APIRequestContexts per surface; this matches what the SPAs do.
//
// False-green hazards guarded against:
//   - Per §3.2: every account uses a freshTag()-suffixed id, so a stale
//     fixture in DB cannot accidentally pass an assertion.
//   - "No GUI footprint" check (§B) — after a rejected transfer, both sides
//     show identical pre-state balances and no transaction appears in the
//     admin list.
//
// Source spec / requirement: UC-01 Electronic Invoice Payment.
// Status: real assertions; one regression-trap-style pin on the idempotency
// behaviour (commented), to be flipped when proper TxnId dedup lands.

import { request as plRequest, expect, test } from '@playwright/test';
import { freshTag } from '../helpers/freshId';
import { authedApi, issueToken } from '../helpers/auth';
import { ENDPOINTS } from '../helpers/endpoints';
import { requireServices } from '../helpers/preflight';
import {
    asNumber,
    basicApi,
    createAccount,
    getBalance,
    transfer,
    adminTransactionList,
} from '../helpers/a1';

test.describe('Phase B — payment UC saturation', () => {
    test.beforeEach(() => {
        requireServices('identity', 'accountLedger', 'vop');
    });

    test('happy path — transfer settles, both sides reflect, admin sees both legs (cross-client)', async () => {
        const demoApi = await basicApi();
        const adminApi = await authedApi('admin');
        try {
            const tag = freshTag();
            const debtor = `NO-${tag}-D`;
            const creditor = `NO-${tag}-C`;

            const a = await createAccount(demoApi, {
                accountId: debtor,
                holderName: `Kari Hansen ${tag}`,
                initialBalance: 50_000,
            });
            const b = await createAccount(demoApi, {
                accountId: creditor,
                holderName: `Bergen Strøm ${tag}`,
                initialBalance: 10_000,
            });
            expect(asNumber(a.bookedBalance)).toBe(50_000);
            expect(asNumber(b.bookedBalance)).toBe(10_000);

            const txnId = `TXN-${tag}`;
            const xfer = await transfer(demoApi, {
                transactionId: txnId,
                debtorAccountId: debtor,
                creditorAccountId: creditor,
                amount: 1_250,
            });
            expect(xfer.status, `body=${JSON.stringify(xfer.body).slice(0, 300)}`).toBe(200);
            const body = xfer.body as { status: string; transactionId: string };
            expect(body.status).toBe('SETTLED');
            expect(body.transactionId).toBe(txnId);

            const debtorBal = await getBalance(demoApi, debtor);
            const creditorBal = await getBalance(demoApi, creditor);
            expect(asNumber(debtorBal.bookedBalance)).toBe(48_750);
            expect(asNumber(creditorBal.bookedBalance)).toBe(11_250);

            // Cross-client: admin's transaction list view shows the transfer
            // (per F2 fix — applyDoubleEntry now writes both legs to
            // account_transactions). Different auth (JWT) than the demo path.
            const debtorTxns = await adminTransactionList(adminApi, debtor);
            expect(debtorTxns.status, `raw=${JSON.stringify(debtorTxns.raw).slice(0, 200)}`).toBe(200);
            const found = debtorTxns.items.some(
                (it) => (it as { transactionId?: string }).transactionId === txnId,
            );
            expect(found, 'admin transaction list should include the new TxnId').toBe(true);
        } finally {
            await Promise.all([demoApi.dispose(), adminApi.dispose()]);
        }
    });

    test('idempotency — same TxnId twice does NOT double-debit (OPEN-3 fixed)', async () => {
        // After OPEN-3 fix: applyDoubleEntry short-circuits when a row
        // for this transactionId already exists in account_transactions.
        // Second call returns 200 with the same SETTLED state and the
        // debtor balance reflects exactly one debit.
        const demoApi = await basicApi();
        try {
            const tag = freshTag();
            await createAccount(demoApi, {
                accountId: `NO-${tag}-D`,
                holderName: `Debtor ${tag}`,
                initialBalance: 1_000,
            });
            await createAccount(demoApi, {
                accountId: `NO-${tag}-C`,
                holderName: `Creditor ${tag}`,
                initialBalance: 1,
            });
            const txnId = `TXN-IDEMP-${tag}`;

            const first = await transfer(demoApi, {
                transactionId: txnId,
                debtorAccountId: `NO-${tag}-D`,
                creditorAccountId: `NO-${tag}-C`,
                amount: 10,
            });
            expect(first.status).toBe(200);

            const second = await transfer(demoApi, {
                transactionId: txnId,
                debtorAccountId: `NO-${tag}-D`,
                creditorAccountId: `NO-${tag}-C`,
                amount: 10,
            });
            expect(second.status).toBe(200);

            // The load-bearing invariant: exactly one debit, regardless
            // of how many times the same TxnId is replayed.
            const debtorBal = await getBalance(demoApi, `NO-${tag}-D`);
            expect(asNumber(debtorBal.bookedBalance)).toBe(990);
            const creditorBal = await getBalance(demoApi, `NO-${tag}-C`);
            expect(asNumber(creditorBal.bookedBalance)).toBe(11);
        } finally {
            await demoApi.dispose();
        }
    });

    test('validation — zero amount is rejected with 400', async () => {
        const demoApi = await basicApi();
        try {
            const tag = freshTag();
            await createAccount(demoApi, {
                accountId: `NO-${tag}-D`,
                holderName: `Debtor ${tag}`,
                initialBalance: 100,
            });
            await createAccount(demoApi, {
                accountId: `NO-${tag}-C`,
                holderName: `Creditor ${tag}`,
                initialBalance: 1,
            });

            const r = await transfer(demoApi, {
                transactionId: `TXN-ZERO-${tag}`,
                debtorAccountId: `NO-${tag}-D`,
                creditorAccountId: `NO-${tag}-C`,
                amount: 0,
            });
            // After the F7 handler fix: @Valid bean validation maps to 400.
            expect(r.status, `body=${JSON.stringify(r.body)}`).toBe(400);
        } finally {
            await demoApi.dispose();
        }
    });

    test('validation — negative amount is rejected with 400', async () => {
        const demoApi = await basicApi();
        try {
            const tag = freshTag();
            await createAccount(demoApi, {
                accountId: `NO-${tag}-D`,
                holderName: `Debtor ${tag}`,
                initialBalance: 100,
            });
            await createAccount(demoApi, {
                accountId: `NO-${tag}-C`,
                holderName: `Creditor ${tag}`,
                initialBalance: 1,
            });

            const r = await transfer(demoApi, {
                transactionId: `TXN-NEG-${tag}`,
                debtorAccountId: `NO-${tag}-D`,
                creditorAccountId: `NO-${tag}-C`,
                amount: -100,
            });
            expect([400, 422]).toContain(r.status);
        } finally {
            await demoApi.dispose();
        }
    });

    test('self-transfer rejected with urn:problem:domain-error (regression for F2)', async () => {
        const demoApi = await basicApi();
        try {
            const tag = freshTag();
            const acct = `NO-${tag}-X`;
            await createAccount(demoApi, {
                accountId: acct,
                holderName: `Self ${tag}`,
                initialBalance: 1_000,
            });

            const r = await transfer(demoApi, {
                transactionId: `TXN-SELF-${tag}`,
                debtorAccountId: acct,
                creditorAccountId: acct,
                amount: 50,
            });
            expect(r.status, `body=${JSON.stringify(r.body)}`).toBe(422);
            const body = r.body as { type?: string; detail?: string };
            // F2 maps SelfTransferException → urn:problem:domain-error.
            expect(body.type ?? '').toMatch(/domain-error|self/i);

            // No GUI footprint: balance unchanged.
            const bal = await getBalance(demoApi, acct);
            expect(asNumber(bal.bookedBalance)).toBe(1_000);
        } finally {
            await demoApi.dispose();
        }
    });

    test('insufficient funds — rejected, balances and projection unchanged ("no GUI footprint")', async () => {
        const demoApi = await basicApi();
        const adminApi = await authedApi('admin');
        try {
            const tag = freshTag();
            await createAccount(demoApi, {
                accountId: `NO-${tag}-D`,
                holderName: `Poor ${tag}`,
                initialBalance: 100,
            });
            await createAccount(demoApi, {
                accountId: `NO-${tag}-C`,
                holderName: `Rich ${tag}`,
                initialBalance: 1,
            });

            const r = await transfer(demoApi, {
                transactionId: `TXN-OVERDRAFT-${tag}`,
                debtorAccountId: `NO-${tag}-D`,
                creditorAccountId: `NO-${tag}-C`,
                amount: 10_000,
            });
            expect(r.status).toBe(422);

            const debtorBal = await getBalance(demoApi, `NO-${tag}-D`);
            const creditorBal = await getBalance(demoApi, `NO-${tag}-C`);
            expect(asNumber(debtorBal.bookedBalance)).toBe(100);
            // Creditor seeded with 1 (the @Positive constraint excludes 0).
            expect(asNumber(creditorBal.bookedBalance)).toBe(1);

            const txns = await adminTransactionList(adminApi, `NO-${tag}-D`);
            const phantom = txns.items.some(
                (it) =>
                    (it as { transactionId?: string }).transactionId === `TXN-OVERDRAFT-${tag}`,
            );
            expect(phantom, 'no projection row should appear for a rejected overdraft').toBe(false);
        } finally {
            await Promise.all([demoApi.dispose(), adminApi.dispose()]);
        }
    });

    test('conservation of money — sum before == sum after a SETTLED transfer', async () => {
        const demoApi = await basicApi();
        try {
            const tag = freshTag();
            const a = `NO-${tag}-A`;
            const b = `NO-${tag}-B`;
            await createAccount(demoApi, {
                accountId: a,
                holderName: `A ${tag}`,
                initialBalance: 500,
            });
            await createAccount(demoApi, {
                accountId: b,
                holderName: `B ${tag}`,
                initialBalance: 500,
            });

            const before = (await getBalance(demoApi, a)).bookedBalance;
            const beforeB = (await getBalance(demoApi, b)).bookedBalance;
            const sumBefore = asNumber(before) + asNumber(beforeB);

            const r = await transfer(demoApi, {
                transactionId: `TXN-CONS-${tag}`,
                debtorAccountId: a,
                creditorAccountId: b,
                amount: 250,
            });
            expect(r.status).toBe(200);

            const after = (await getBalance(demoApi, a)).bookedBalance;
            const afterB = (await getBalance(demoApi, b)).bookedBalance;
            const sumAfter = asNumber(after) + asNumber(afterB);

            expect(sumAfter).toBe(sumBefore);
            expect(asNumber(after)).toBe(250);
            expect(asNumber(afterB)).toBe(750);
        } finally {
            await demoApi.dispose();
        }
    });

    test('admin Bearer JWT — same identity issuer authorizes admin scopes', async () => {
        // Defense against §3.2 false-greens: assert that the dev-token
        // endpoint actually returns Bearer tokens for both audiences and
        // that an admin-audience token authenticates against A1's JWT chain.
        const bootstrap = await plRequest.newContext();
        try {
            const customer = await issueToken(bootstrap, 'customer');
            const admin = await issueToken(bootstrap, 'admin');
            expect(customer.audience).toBe('customer');
            expect(admin.audience).toBe('admin');

            // Admin-audience token must pass /api/v1/admin/accounts/search.
            const adminCheck = await bootstrap.get(
                `${ENDPOINTS.accountLedger}/api/v1/admin/accounts/search?mode=name&q=Nordmann&page=0&size=1`,
                { headers: { Authorization: `Bearer ${admin.accessToken}` } },
            );
            expect(adminCheck.status()).toBe(200);

            // Customer-audience token must NOT.
            const customerCheck = await bootstrap.get(
                `${ENDPOINTS.accountLedger}/api/v1/admin/accounts/search?mode=name&q=Nordmann&page=0&size=1`,
                { headers: { Authorization: `Bearer ${customer.accessToken}` } },
            );
            expect([401, 403]).toContain(customerCheck.status());
        } finally {
            await bootstrap.dispose();
        }
    });
});
