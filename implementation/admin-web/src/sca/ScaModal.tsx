// UC-G51 SCA modal. Two phases:
//   1. Initiating  — POST /sca/start, displays the challenge metadata.
//   2. Awaiting code — operator types the TOTP, POST /sca/{sessionId}/verify.
//
// On success, returns the step-up token via the onResolve callback. Caller
// (e.g. the decide button) then replays its mutating request with the token
// in the Step-Up-Token header.
//
// Focus is trapped while the modal is open (FA.7); ESC cancels.

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AdminActionScaInput, startSca, verifySca } from './scaClient';

type Phase = 'starting' | 'awaiting-code' | 'verifying' | 'done' | 'error';

export interface ScaModalProps {
    inputs: AdminActionScaInput;
    onResolve: (stepUpToken: string) => void;
    onCancel: () => void;
    titleKey?: string;
    descriptionKey?: string;
}

export function ScaModal({ inputs, onResolve, onCancel, titleKey, descriptionKey }: ScaModalProps) {
    const { t } = useTranslation();
    const [phase, setPhase] = useState<Phase>('starting');
    const [sessionId, setSessionId] = useState<string | null>(null);
    const [factorType, setFactorType] = useState<string>('TOTP');
    const [code, setCode] = useState('');
    const [error, setError] = useState<string | null>(null);
    const codeInputRef = useRef<HTMLInputElement>(null);
    const dialogRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        let cancelled = false;
        startSca(inputs)
            .then((res) => {
                if (cancelled) return;
                if (res.exempt && res.stepUpToken) {
                    onResolve(res.stepUpToken);
                    return;
                }
                setSessionId(res.scaSessionId);
                setFactorType(res.challenge?.type ?? 'TOTP');
                setPhase('awaiting-code');
            })
            .catch((e: unknown) => {
                if (cancelled) return;
                setError(e instanceof Error ? e.message : String(e));
                setPhase('error');
            });
        return () => {
            cancelled = true;
        };
    }, [inputs, onResolve]);

    useEffect(() => {
        if (phase === 'awaiting-code') {
            codeInputRef.current?.focus();
        }
    }, [phase]);

    useEffect(() => {
        function onKey(e: KeyboardEvent) {
            if (e.key === 'Escape') onCancel();
        }
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onCancel]);

    async function submit() {
        if (!sessionId || !code) return;
        setPhase('verifying');
        try {
            const res = await verifySca(sessionId, { type: 'TOTP', code });
            setPhase('done');
            onResolve(res.stepUpToken);
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
            setPhase('error');
        }
    }

    return (
        <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="sca-title"
            ref={dialogRef}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4"
        >
            <div className="bg-white rounded-lg shadow-xl max-w-md w-full p-6">
                <h2 id="sca-title" className="text-lg font-semibold text-jrpay-navy">
                    {t(titleKey ?? 'sca.title')}
                </h2>
                <p className="text-sm text-slate-600 mt-1">
                    {t(descriptionKey ?? 'sca.description')}
                </p>

                {phase === 'starting' && (
                    <p className="mt-6 text-sm" aria-live="polite">
                        {t('sca.initiating')}
                    </p>
                )}

                {phase === 'awaiting-code' && (
                    <div className="mt-6 space-y-3">
                        <p className="text-xs text-slate-500">
                            {t('sca.factor', { type: factorType })}
                        </p>
                        <label htmlFor="sca-code" className="block text-sm font-medium">
                            {t('sca.codeLabel')}
                        </label>
                        <input
                            ref={codeInputRef}
                            id="sca-code"
                            type="text"
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            pattern="[0-9]{6}"
                            maxLength={6}
                            value={code}
                            onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                            className="input"
                        />
                    </div>
                )}

                {phase === 'verifying' && (
                    <p className="mt-6 text-sm" aria-live="polite">
                        {t('sca.verifying')}
                    </p>
                )}

                {phase === 'error' && error && (
                    <p className="mt-4 text-sm text-jrpay-red" role="alert">
                        {error}
                    </p>
                )}

                <div className="mt-6 flex justify-end gap-2">
                    <button className="btn-secondary" onClick={onCancel}>
                        {t('common.cancel')}
                    </button>
                    {phase === 'awaiting-code' && (
                        <button
                            className="btn-primary"
                            onClick={submit}
                            disabled={code.length !== 6}
                        >
                            {t('sca.verify')}
                        </button>
                    )}
                    {phase === 'error' && (
                        <button
                            className="btn-primary"
                            onClick={() => {
                                setPhase('starting');
                                setError(null);
                            }}
                        >
                            {t('common.retry')}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
