// UC-G11 / UC-G12 / UC-G13 / UC-G14 — Request-to-Pay inbox.
//
// Lists pending R2P requests with four customer decisions:
//   - Accept → triggers payment-initiation SCA, then submit
//   - Decline → simple POST with reason code (no SCA — read-only intent)
//   - Defer → POST with new due date
//   - Amend → POST counter-proposal with new amount/due date
//
// Live updates via SSE on /api/v1/customer/r2p/stream (single-use stream
// token mint at /stream-token, mirroring the admin pattern).

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { paymentInitiationDlHash } from '../../sca/dlHash';
import { formatAmount, formatDate, formatDateTime } from '../../lib/format';

type R2pStatus =
    | 'PENDING'
    | 'ACCEPTED'
    | 'DECLINED'
    | 'DEFERRED'
    | 'AMENDMENT_REQUESTED'
    | 'EXPIRED';

interface R2pRequest {
    requestId: string;
    creditorName: string;
    creditorIban: string;
    debtorAccountId: string;
    amount: string;
    currency: string;
    reference?: string;
    dueDate?: string;
    receivedAt: string;
    expiresAt?: string;
    status: R2pStatus;
}

const DECLINE_REASONS = [
    'INCORRECT_AMOUNT',
    'NOT_RECOGNISED',
    'DUPLICATE',
    'GOODS_NOT_RECEIVED',
    'OTHER',
];

export function R2pInboxPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [requests, setRequests] = useState<R2pRequest[] | null>(null);
    const [refresh, setRefresh] = useState(0);
    const [decideOf, setDecideOf] = useState<R2pRequest | null>(null);
    const [livePulse, setLivePulse] = useState(false);

    const reload = useCallback(() => {
        api<{ items: R2pRequest[] }>('/api/v1/customer/r2p', {
            query: { status: ['PENDING', 'AMENDMENT_REQUESTED'] },
        })
            .then((r) => setRequests(r.items ?? []))
            .catch((e) => showProblem(e));
    }, [showProblem]);

    useEffect(() => {
        reload();
    }, [reload, refresh]);

    // SSE wiring.
    useEffect(() => {
        let closed = false;
        let source: EventSource | null = null;
        let timer: ReturnType<typeof setTimeout> | null = null;

        async function connect() {
            if (closed) return;
            try {
                const tok = await api<{ streamToken: string }>(
                    '/api/v1/customer/r2p/stream-token',
                    { method: 'POST', body: {} }
                );
                if (closed) return;
                source = new EventSource(
                    `/api/v1/customer/r2p/stream?token=${encodeURIComponent(tok.streamToken)}`
                );
                const onAny = () => {
                    setLivePulse(true);
                    setTimeout(() => setLivePulse(false), 1_500);
                    setRefresh((n) => n + 1);
                };
                source.addEventListener('r2p.received', onAny);
                source.addEventListener('r2p.amended', onAny);
                source.addEventListener('r2p.expired', onAny);
                source.onerror = () => {
                    source?.close();
                    source = null;
                    if (!closed) timer = setTimeout(connect, 5_000);
                };
            } catch {
                if (!closed) timer = setTimeout(connect, 10_000);
            }
        }
        connect();
        return () => {
            closed = true;
            if (timer) clearTimeout(timer);
            source?.close();
        };
    }, []);

    return (
        <section aria-labelledby="r2p-title">
            <header className="flex items-baseline justify-between flex-wrap gap-3">
                <h1 id="r2p-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('r2p.title')}
                </h1>
                {livePulse && (
                    <span className="badge badge-info" role="status">
                        ● Live
                    </span>
                )}
            </header>

            <div className="space-y-3 mt-4">
                {requests === null && <p className="text-slate-500">{t('common.loading')}</p>}
                {requests && requests.length === 0 && (
                    <p className="text-slate-500">{t('r2p.noRequests')}</p>
                )}
                {requests?.map((r) => (
                    <article
                        key={r.requestId}
                        className="card p-4 flex flex-wrap items-start justify-between gap-3"
                        aria-label={`R2P from ${r.creditorName}`}
                    >
                        <div>
                            <h2 className="text-lg font-semibold">{r.creditorName}</h2>
                            <p className="text-xs font-mono text-slate-500 mt-1">
                                {r.creditorIban}
                            </p>
                            <p className="text-2xl tabular-nums text-jrpay-navy mt-2">
                                {formatAmount(r.amount, r.currency, i18n.language)}
                            </p>
                            <p className="text-xs text-slate-500 mt-1">
                                {t('r2p.due')}: {r.dueDate ? formatDate(r.dueDate, i18n.language) : '—'}{' '}
                                · {t('r2p.from')} {formatDateTime(r.receivedAt, i18n.language)}
                            </p>
                            {r.reference && (
                                <p className="text-xs text-slate-700 mt-1">
                                    {t('accounts.reference')}: {r.reference}
                                </p>
                            )}
                        </div>
                        <div className="flex flex-wrap gap-2">
                            <button className="btn-primary" onClick={() => setDecideOf(r)}>
                                {t('r2p.accept')}
                            </button>
                        </div>
                    </article>
                ))}
            </div>

            {decideOf && (
                <DecideModal
                    request={decideOf}
                    onClose={() => setDecideOf(null)}
                    onDone={() => {
                        setDecideOf(null);
                        showSuccess(t('common.confirm'));
                        reload();
                    }}
                />
            )}
        </section>
    );
}

type Choice = 'ACCEPT' | 'DECLINE' | 'DEFER' | 'AMEND';

function DecideModal({
    request,
    onClose,
    onDone,
}: {
    request: R2pRequest;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [choice, setChoice] = useState<Choice>('ACCEPT');
    const [declineReason, setDeclineReason] = useState(DECLINE_REASONS[0]!);
    const [deferDate, setDeferDate] = useState(
        request.dueDate ?? new Date().toISOString().slice(0, 10)
    );
    const [amendAmount, setAmendAmount] = useState(request.amount);
    const [amendDueDate, setAmendDueDate] = useState(request.dueDate ?? '');
    const [scaOpen, setScaOpen] = useState(false);
    const [hex, setHex] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        setSubmitting(true);
        try {
            if (choice === 'ACCEPT') {
                const { hex: h } = await paymentInitiationDlHash({
                    creditorIban: request.creditorIban,
                    debtorAccountId: request.debtorAccountId,
                    amount: request.amount,
                    currency: request.currency,
                    reference: request.reference,
                });
                setHex(h);
                setScaOpen(true);
            } else {
                await postNonScaDecision();
            }
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function postNonScaDecision() {
        try {
            const path = `/api/v1/customer/r2p/${encodeURIComponent(request.requestId)}`;
            if (choice === 'DECLINE') {
                await api(`${path}/decline`, {
                    method: 'POST',
                    body: { reason: declineReason },
                });
            } else if (choice === 'DEFER') {
                await api(`${path}/defer`, {
                    method: 'POST',
                    body: { until: deferDate },
                });
            } else if (choice === 'AMEND') {
                await api(`${path}/amend`, {
                    method: 'POST',
                    body: {
                        proposedAmount: amendAmount,
                        proposedDueDate: amendDueDate || undefined,
                    },
                });
            }
            onDone();
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    async function postAccept(stepUpToken: string) {
        try {
            await api(
                `/api/v1/customer/r2p/${encodeURIComponent(request.requestId)}/accept`,
                {
                    method: 'POST',
                    stepUpToken,
                    body: {},
                }
            );
            onDone();
        } catch (e) {
            if (e instanceof ProblemError && e.isStepUpRequired) {
                setScaOpen(true);
                setSubmitting(false);
                return;
            }
            showProblem(e);
            setSubmitting(false);
        }
    }

    if (scaOpen && hex) {
        return (
            <ScaModal
                inputs={{
                    class: 'payment-initiation',
                    creditorIban: request.creditorIban,
                    debtorAccountId: request.debtorAccountId,
                    amount: request.amount,
                    currency: request.currency,
                    reference: request.reference,
                    notesHash: hex,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void postAccept(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <Modal titleId="r2p-decide-title" onClose={onClose}>
            <h2 id="r2p-decide-title" className="text-lg font-semibold text-jrpay-navy">
                {request.creditorName}
            </h2>
            <p className="text-sm text-slate-600 mt-1">
                {request.creditorIban}
            </p>
            <p className="text-2xl tabular-nums mt-2">
                {formatAmount(request.amount, request.currency, 'nb-NO')}
            </p>

            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <fieldset className="grid grid-cols-2 gap-2">
                    <legend className="sr-only">Decision</legend>
                    {(['ACCEPT', 'DECLINE', 'DEFER', 'AMEND'] as const).map((c) => (
                        <label
                            key={c}
                            className={`px-3 py-2 rounded-md border cursor-pointer text-center ${
                                choice === c
                                    ? 'bg-jrpay-navy text-white border-jrpay-navy'
                                    : 'bg-white text-slate-700 border-slate-300 hover:border-jrpay-navy'
                            }`}
                        >
                            <input
                                type="radio"
                                name="choice"
                                value={c}
                                checked={choice === c}
                                onChange={() => setChoice(c)}
                                className="sr-only"
                            />
                            {t(`r2p.${c.toLowerCase()}`)}
                        </label>
                    ))}
                </fieldset>

                {choice === 'DECLINE' && (
                    <label className="block">
                        {t('r2p.declineReason')}
                        <select
                            className="input mt-1"
                            value={declineReason}
                            onChange={(e) => setDeclineReason(e.target.value)}
                        >
                            {DECLINE_REASONS.map((r) => (
                                <option key={r} value={r}>
                                    {r}
                                </option>
                            ))}
                        </select>
                    </label>
                )}

                {choice === 'DEFER' && (
                    <label className="block">
                        {t('r2p.deferUntil')}
                        <input
                            type="date"
                            className="input mt-1"
                            value={deferDate}
                            onChange={(e) => setDeferDate(e.target.value)}
                            min={new Date().toISOString().slice(0, 10)}
                            required
                        />
                    </label>
                )}

                {choice === 'AMEND' && (
                    <>
                        <label className="block">
                            {t('r2p.amendNewAmount')}
                            <input
                                type="number"
                                step="0.01"
                                className="input mt-1 tabular-nums"
                                value={amendAmount}
                                onChange={(e) => setAmendAmount(e.target.value)}
                                required
                            />
                        </label>
                        <label className="block">
                            {t('r2p.amendNewDueDate')}
                            <input
                                type="date"
                                className="input mt-1"
                                value={amendDueDate}
                                onChange={(e) => setAmendDueDate(e.target.value)}
                            />
                        </label>
                    </>
                )}

                <div className="flex justify-end gap-2 mt-2">
                    <button
                        type="button"
                        className="btn-secondary"
                        onClick={onClose}
                        disabled={submitting}
                    >
                        {t('common.cancel')}
                    </button>
                    <button
                        type="submit"
                        className="btn-primary"
                        disabled={submitting}
                    >
                        {t('common.confirm')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}
