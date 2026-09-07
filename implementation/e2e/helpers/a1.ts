// Account-ledger (A1) helpers shared across phaseB-* and downstream specs.
//
// A1 has two REST surfaces with different auth chains:
//   - /api/v1/accounts/**  — legacy "demo" surface (createAccount, /transfer,
//     /balances). Gated by HTTP Basic (admin/admin) per the original
//     DemoSecurityConfig. F1 did NOT extend the JWT chain here; this is by
//     design for the demo path.
//   - /api/v1/admin/**     — admin SPA surface. Gated by Bearer JWT (post-F1).
//   - /api/v1/customer/**  — customer SPA surface. Gated by Bearer JWT (post-F1).
//
// Use the appropriate context per call: `basicApi()` for the legacy demo
// surface, `authedApi('admin')` from helpers/auth.ts for /api/v1/admin/**.

import { type APIRequestContext, request as plRequest, expect } from '@playwright/test';
import { ENDPOINTS } from './endpoints';

/** Returns an APIRequestContext authed via HTTP Basic admin/admin for the
 *  legacy /api/v1/accounts/** surface. The dispose call is the caller's
 *  responsibility. */
export async function basicApi(): Promise<APIRequestContext> {
    const basic = Buffer.from('admin:admin').toString('base64');
    return await plRequest.newContext({
        extraHTTPHeaders: { Authorization: `Basic ${basic}` },
    });
}

export interface CreatedAccount {
    accountId: string;
    holderName: string;
    currency: string;
    bookedBalance: string;
    availableBalance: string;
    status: string;
}

export interface BalanceResponse {
    accountId: string;
    bookedBalance: string;
    reservedAmount: string;
    availableBalance: string;
    currency: string;
}

export interface TransferResponse {
    transactionId: string;
    status: 'SETTLED' | string;
    debtor: CreatedAccount;
    creditor: CreatedAccount;
}

export async function createAccount(
    api: APIRequestContext,
    args: { accountId: string; holderName: string; currency?: string; initialBalance: number },
): Promise<CreatedAccount> {
    const res = await api.post(`${ENDPOINTS.accountLedger}/api/v1/accounts`, {
        headers: { 'Content-Type': 'application/json' },
        data: {
            accountId: args.accountId,
            holderName: args.holderName,
            currency: args.currency ?? 'NOK',
            initialBalance: args.initialBalance,
        },
    });
    expect(res.status(), `createAccount(${args.accountId}) body=${(await res.text()).slice(0, 200)}`)
        .toBe(201);
    return (await res.json()) as CreatedAccount;
}

export async function getBalance(
    api: APIRequestContext,
    accountId: string,
): Promise<BalanceResponse> {
    const res = await api.get(
        `${ENDPOINTS.accountLedger}/api/v1/accounts/${encodeURIComponent(accountId)}/balances`,
    );
    expect(res.status(), `getBalance(${accountId}) body=${(await res.text()).slice(0, 200)}`)
        .toBe(200);
    return (await res.json()) as BalanceResponse;
}

export async function transfer(
    api: APIRequestContext,
    args: {
        transactionId: string;
        debtorAccountId: string;
        creditorAccountId: string;
        amount: number;
        currency?: string;
        description?: string;
    },
): Promise<{ status: number; body: TransferResponse | Record<string, unknown> }> {
    const res = await api.post(`${ENDPOINTS.accountLedger}/api/v1/accounts/transfer`, {
        headers: { 'Content-Type': 'application/json' },
        data: {
            transactionId: args.transactionId,
            debtorAccountId: args.debtorAccountId,
            creditorAccountId: args.creditorAccountId,
            amount: args.amount,
            currency: args.currency ?? 'NOK',
            description: args.description ?? 'e2e test payment',
        },
    });
    const text = await res.text();
    let body: TransferResponse | Record<string, unknown>;
    try {
        body = JSON.parse(text) as TransferResponse;
    } catch {
        body = { raw: text };
    }
    return { status: res.status(), body };
}

export async function adminTransactionList(
    adminApi: APIRequestContext,
    accountId: string,
): Promise<{ status: number; items: unknown[]; raw: unknown }> {
    const res = await adminApi.get(
        `${ENDPOINTS.accountLedger}/api/v1/admin/accounts/${encodeURIComponent(accountId)}/transactions?page=0&size=50`,
    );
    const status = res.status();
    if (status !== 200) {
        return { status, items: [], raw: await res.text() };
    }
    const body = (await res.json()) as { items?: unknown[] };
    return { status, items: body.items ?? [], raw: body };
}

export function asNumber(s: string): number {
    return Number.parseFloat(s);
}
