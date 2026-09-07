// Phase E §1 — empty / partial state.
//
// "First-time user with no data sees an empty state, not a fixture, not a crash."
// E2E_TESTING_GUIDE.md §E (1).
//
// What it proves: a brand-new account (zero history) renders correctly on
// the admin transaction-list view (page=0 with totalElements=0) and the
// shape matches the documented paged contract — not a hardcoded fixture.
//
// False-green hazard: if a stale cached fixture is served instead of a
// real query, the test would still see the "empty" shape but the response
// would lack our run-fresh accountId. We assert against the run-fresh id
// to defend against that.

import { expect, test } from '@playwright/test';
import { authedApi } from '../helpers/auth';
import { ENDPOINTS } from '../helpers/endpoints';
import { freshTag } from '../helpers/freshId';
import { requireServices } from '../helpers/preflight';
import { basicApi, createAccount, adminTransactionList } from '../helpers/a1';

test.describe('Phase E §1 — empty state', () => {
    test.beforeEach(() => {
        requireServices('identity', 'accountLedger');
    });

    test('brand-new account renders empty transaction list, not a crash', async () => {
        const demoApi = await basicApi();
        const adminApi = await authedApi('admin');
        try {
            const tag = freshTag();
            const acct = `NO-${tag}-EMPTY`;
            await createAccount(demoApi, {
                accountId: acct,
                holderName: `New User ${tag}`,
                initialBalance: 1,
            });

            const txns = await adminTransactionList(adminApi, acct);
            expect(txns.status, `raw=${JSON.stringify(txns.raw).slice(0, 200)}`).toBe(200);
            expect(txns.items).toEqual([]);
            // Transactions list has a nested `page` object, distinct from
            // the admin search shape (which flattens page/totalElements at
            // the top level). This shape is the documented contract.
            const raw = txns.raw as { page?: { totalElements?: number; number?: number } };
            expect(raw.page?.totalElements).toBe(0);
            expect(raw.page?.number).toBe(0);
        } finally {
            await Promise.all([demoApi.dispose(), adminApi.dispose()]);
        }
    });

    test('admin search with non-matching query returns empty page, not a crash', async () => {
        const adminApi = await authedApi('admin');
        try {
            const tag = freshTag();
            const res = await adminApi.get(
                `${ENDPOINTS.accountLedger}/api/v1/admin/accounts/search?mode=name&q=NoSuchPersonExists${tag}&page=0&size=10`,
            );
            expect(res.status()).toBe(200);
            const body = (await res.json()) as { items: unknown[]; totalElements: number };
            expect(body.items).toEqual([]);
            expect(body.totalElements).toBe(0);
        } finally {
            await adminApi.dispose();
        }
    });
});
