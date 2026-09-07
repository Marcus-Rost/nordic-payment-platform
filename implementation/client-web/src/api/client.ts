// Centralised typed fetch wrapper for the customer surface.
//
// Same shape as admin-web's client. Differences:
//   - Customer JWT does not carry `aud=admin`; nothing else changes.
//   - Idempotency-Key is required on payment-mutation endpoints (server-side).

import { decodeProblem, ProblemError } from './problem';

export type ApiMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export interface ApiCallOptions {
    method?: ApiMethod;
    body?: unknown;
    query?: Record<string, string | number | boolean | string[] | undefined>;
    idempotencyKey?: string;
    stepUpToken?: string;
    bearer?: string;
    signal?: AbortSignal;
    headers?: Record<string, string>;
}

export type BearerProvider = () => string | null;

let bearerProvider: BearerProvider = () => null;

export function setBearerProvider(provider: BearerProvider): void {
    bearerProvider = provider;
}

export function newCorrelationId(): string {
    return crypto.randomUUID();
}

export function newIdempotencyKey(): string {
    return crypto.randomUUID();
}

export async function api<T = unknown>(path: string, opts: ApiCallOptions = {}): Promise<T> {
    const method = opts.method ?? 'GET';
    const url = buildUrl(path, opts.query);
    const headers = new Headers(opts.headers ?? {});

    const bearer = opts.bearer ?? bearerProvider();
    if (bearer) {
        headers.set('Authorization', `Bearer ${bearer}`);
    }
    if (opts.stepUpToken) {
        headers.set('Step-Up-Token', opts.stepUpToken);
    }
    if (method !== 'GET') {
        headers.set('Idempotency-Key', opts.idempotencyKey ?? newIdempotencyKey());
    }
    if (opts.body !== undefined && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }
    headers.set('traceparent', traceparent());

    const init: RequestInit = {
        method,
        headers,
        signal: opts.signal,
    };
    if (opts.body !== undefined) {
        init.body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
    }

    const response = await fetch(url, init);
    if (!response.ok) {
        throw await decodeProblem(response);
    }
    if (response.status === 204) {
        return undefined as T;
    }
    const contentType = response.headers.get('Content-Type') ?? '';
    if (contentType.includes('application/json')) {
        return (await response.json()) as T;
    }
    return (await response.text()) as unknown as T;
}

function buildUrl(path: string, query?: ApiCallOptions['query']): string {
    if (!query) return path;
    const url = new URL(path, window.location.origin);
    for (const [key, value] of Object.entries(query)) {
        if (value === undefined || value === null) continue;
        if (Array.isArray(value)) {
            for (const v of value) url.searchParams.append(key, String(v));
        } else {
            url.searchParams.set(key, String(value));
        }
    }
    return url.pathname + url.search;
}

function traceparent(): string {
    const trace = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
    const span = bytesToHex(crypto.getRandomValues(new Uint8Array(8)));
    return `00-${trace}-${span}-01`;
}

function bytesToHex(bytes: Uint8Array): string {
    return Array.from(bytes)
        .map((b) => b.toString(16).padStart(2, '0'))
        .join('');
}

export { ProblemError };
