// UC-G16 — trusted-beneficiaries CRUD with VoP at add-time.
//
// Adding a beneficiary runs a VoP check first; if MISMATCH the customer is
// blocked, on CLOSE_MATCH a confirmation is required, on UNAVAILABLE the
// customer can proceed with an explicit "I accept the risk" tick.
//
// Marking a beneficiary as trusted is what unlocks the SCA-light path on
// future payments (per the dynamic-linking spec, trusted-beneficiary
// payments are exempt from per-payment SCA).

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';

interface Beneficiary {
    beneficiaryId: string;
    name: string;
    iban: string;
    trusted: boolean;
    vopBound?: boolean;
    vopBoundAt?: string;
    addedAt: string;
}

type VopResult = 'MATCH' | 'CLOSE_MATCH' | 'MISMATCH' | 'NO_MATCH' | 'UNAVAILABLE';

export function BeneficiariesPage() {
    const { t } = useTranslation();
    const { showProblem, showSuccess } = useToasts();
    const [items, setItems] = useState<Beneficiary[] | null>(null);
    const [refresh, setRefresh] = useState(0);
    const [addOpen, setAddOpen] = useState(false);

    useEffect(() => {
        api<{ items: Beneficiary[] }>('/api/v1/customer/beneficiaries')
            .then((r) => setItems(r.items ?? []))
            .catch((e) => showProblem(e));
    }, [refresh, showProblem]);

    async function toggleTrust(b: Beneficiary) {
        try {
            await api(
                `/api/v1/customer/beneficiaries/${encodeURIComponent(b.beneficiaryId)}/trust`,
                { method: 'POST', body: { trusted: !b.trusted } }
            );
            showSuccess(b.trusted ? t('beneficiaries.untrust') : t('beneficiaries.trust'));
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    async function remove(b: Beneficiary) {
        try {
            await api(`/api/v1/customer/beneficiaries/${encodeURIComponent(b.beneficiaryId)}`, {
                method: 'DELETE',
            });
            showSuccess(t('common.delete'));
            setRefresh((n) => n + 1);
        } catch (e) {
            showProblem(e);
        }
    }

    return (
        <section aria-labelledby="ben-title">
            <header className="flex items-baseline justify-between flex-wrap gap-3">
                <h1 id="ben-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('beneficiaries.title')}
                </h1>
                <button className="btn-primary" onClick={() => setAddOpen(true)}>
                    {t('beneficiaries.addNew')}
                </button>
            </header>

            <div className="card mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('beneficiaries.name')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('beneficiaries.iban')}
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('beneficiaries.trusted')}
                            </th>
                            <th scope="col" className="text-right px-3 py-2">
                                {t('common.actions')}
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {items === null && (
                            <tr>
                                <td colSpan={4} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {items && items.length === 0 && (
                            <tr>
                                <td colSpan={4} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.noResults')}
                                </td>
                            </tr>
                        )}
                        {items?.map((b) => (
                            <tr key={b.beneficiaryId} className="border-t border-slate-100">
                                <td className="px-3 py-2">{b.name}</td>
                                <td className="px-3 py-2 font-mono text-xs">{b.iban}</td>
                                <td className="px-3 py-2">
                                    {b.trusted ? (
                                        <span className="badge badge-success">
                                            {t('beneficiaries.trusted')}
                                        </span>
                                    ) : (
                                        <span className="badge badge-neutral">—</span>
                                    )}
                                    {b.vopBound && (
                                        <span className="ml-2 badge badge-info">
                                            {t('beneficiaries.vopBound')}
                                        </span>
                                    )}
                                </td>
                                <td className="px-3 py-2 text-right">
                                    <div className="flex justify-end gap-1">
                                        <button
                                            className="btn-secondary text-xs py-1"
                                            onClick={() => toggleTrust(b)}
                                        >
                                            {b.trusted
                                                ? t('beneficiaries.untrust')
                                                : t('beneficiaries.trust')}
                                        </button>
                                        <button
                                            className="btn-danger text-xs py-1"
                                            onClick={() => remove(b)}
                                        >
                                            {t('beneficiaries.remove')}
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {addOpen && (
                <AddBeneficiaryModal
                    onClose={() => setAddOpen(false)}
                    onDone={() => {
                        setAddOpen(false);
                        setRefresh((n) => n + 1);
                        showSuccess(t('beneficiaries.addNew'));
                    }}
                />
            )}
        </section>
    );
}

function AddBeneficiaryModal({
    onClose,
    onDone,
}: {
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [name, setName] = useState('');
    const [iban, setIban] = useState('');
    const [vopResult, setVopResult] = useState<VopResult | null>(null);
    const [vopActualName, setVopActualName] = useState<string | undefined>();
    const [acceptRisk, setAcceptRisk] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    async function runVop() {
        if (!name.trim() || !iban.trim()) return;
        setVopResult(null);
        try {
            const res = await api<{ result: VopResult; actualNameMasked?: string }>(
                '/api/v1/customer/payments/verify-payee',
                {
                    method: 'POST',
                    body: { creditorIban: iban.trim(), creditorName: name.trim() },
                }
            );
            setVopResult(res.result);
            setVopActualName(res.actualNameMasked);
        } catch (e) {
            if (e instanceof ProblemError && e.isVopUnavailable) {
                setVopResult('UNAVAILABLE');
            } else {
                showProblem(e);
            }
        }
    }

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        if (vopResult === 'MISMATCH') return;
        if (vopResult === 'UNAVAILABLE' && !acceptRisk) return;
        setSubmitting(true);
        try {
            await api('/api/v1/customer/beneficiaries', {
                method: 'POST',
                body: {
                    name: name.trim(),
                    iban: iban.trim(),
                    vopResult,
                    acceptVopUnavailable: vopResult === 'UNAVAILABLE' ? true : undefined,
                },
            });
            onDone();
        } catch (e) {
            showProblem(e);
            setSubmitting(false);
        }
    }

    const blocked = vopResult === 'MISMATCH';
    const needsAcknowledge = vopResult === 'UNAVAILABLE' || vopResult === 'CLOSE_MATCH';
    const canSubmit =
        !!name.trim() && !!iban.trim() && vopResult !== null && !blocked &&
        (!needsAcknowledge || acceptRisk);

    return (
        <Modal titleId="add-ben-title" onClose={onClose}>
            <h2 id="add-ben-title" className="text-lg font-semibold text-jrpay-navy">
                {t('beneficiaries.addNew')}
            </h2>
            <form onSubmit={submit} className="mt-4 space-y-3 text-sm">
                <label className="block">
                    {t('beneficiaries.name')}
                    <input
                        className="input mt-1"
                        value={name}
                        onChange={(e) => {
                            setName(e.target.value);
                            setVopResult(null);
                        }}
                        required
                    />
                </label>
                <label className="block">
                    {t('beneficiaries.iban')}
                    <input
                        className="input mt-1 font-mono"
                        value={iban}
                        onChange={(e) => {
                            setIban(e.target.value.toUpperCase());
                            setVopResult(null);
                        }}
                        required
                    />
                </label>
                <button type="button" className="btn-secondary" onClick={runVop}>
                    {t('payments.verifyOfPayee')}
                </button>
                {vopResult === 'MATCH' && (
                    <p className="badge badge-success">{t('payments.vopMatch')}</p>
                )}
                {vopResult === 'CLOSE_MATCH' && (
                    <p className="badge badge-warn">
                        {t('payments.vopClose')}
                        {vopActualName && (
                            <span className="ml-1 font-mono">→ {vopActualName}</span>
                        )}
                    </p>
                )}
                {vopResult === 'MISMATCH' && (
                    <p className="badge badge-danger">{t('payments.vopMismatch')}</p>
                )}
                {vopResult === 'UNAVAILABLE' && (
                    <p className="badge badge-warn">{t('payments.vopUnavailable')}</p>
                )}
                {needsAcknowledge && (
                    <label className="flex items-start gap-2 mt-2 cursor-pointer">
                        <input
                            type="checkbox"
                            checked={acceptRisk}
                            onChange={(e) => setAcceptRisk(e.target.checked)}
                        />
                        <span>I accept the risk and want to add this beneficiary anyway.</span>
                    </label>
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
                        disabled={!canSubmit || submitting}
                    >
                        {t('common.save')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}
