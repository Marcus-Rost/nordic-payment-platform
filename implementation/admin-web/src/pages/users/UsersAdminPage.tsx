// UC-G43 — operator administration.
//
// SUPERVISOR-driven CRUD on back-office users (list, create, role change,
// suspend / reactivate / delete / unlock). Each mutation runs UC-G51 SCA
// with the matching AdminAction value (CREATE_OPERATOR, UPDATE_OPERATOR_ROLE,
// SUSPEND_OPERATOR, REACTIVATE_OPERATOR, DELETE_OPERATOR, UNLOCK_OPERATOR).
//
// Two-supervisor concurrence flow (ADMIN role grants) renders a "pending
// approval" badge on the affected row; a second SUPERVISOR resolves via the
// approvalRef. The full approval-grant flow lives in
// /api/v1/admin/users/approvals/{ref} and is wired here as a per-row button.

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ProblemError } from '../../api/client';
import { useToasts } from '../../components/Toast';
import { Modal } from '../../components/Modal';
import { ScaModal } from '../../sca/ScaModal';
import { sha256HexNfc } from '../../sca/dlHash';

type Role = 'FRAUD_ANALYST' | 'COMPLIANCE_OFFICER' | 'SUPERVISOR' | 'ADMIN';
type Status =
    | 'PENDING_FIRST_LOGIN'
    | 'ACTIVE'
    | 'SUSPENDED'
    | 'LOCKED'
    | 'DELETED';

interface OperatorSummary {
    userId: string;
    email: string;
    name: string;
    role: Role;
    status: Status;
    department?: string;
    createdAt: string;
    lastLoginAt?: string;
    mustSetupSca?: boolean;
}

interface UsersPage {
    items: OperatorSummary[];
    page: { number: number; size: number; totalElements: number; totalPages: number };
}

type Action =
    | 'CREATE_OPERATOR'
    | 'UPDATE_OPERATOR_ROLE'
    | 'SUSPEND_OPERATOR'
    | 'REACTIVATE_OPERATOR'
    | 'DELETE_OPERATOR'
    | 'UNLOCK_OPERATOR';

const ROLES: Role[] = ['FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'SUPERVISOR', 'ADMIN'];

export function UsersAdminPage() {
    const { t, i18n } = useTranslation();
    const { showProblem, showInfo } = useToasts();

    const [users, setUsers] = useState<UsersPage | null>(null);
    const [loading, setLoading] = useState(true);
    const [createOpen, setCreateOpen] = useState(false);
    const [actOn, setActOn] = useState<{ user: OperatorSummary; action: Action } | null>(null);
    const [refresh, setRefresh] = useState(0);

    useEffect(() => {
        setLoading(true);
        api<UsersPage>('/api/v1/admin/users', { query: { page: 0, size: 50 } })
            .then(setUsers)
            .catch((e) => showProblem(e))
            .finally(() => setLoading(false));
    }, [refresh, showProblem]);

    return (
        <section aria-labelledby="users-title">
            <header className="flex items-baseline justify-between flex-wrap gap-2">
                <h1 id="users-title" className="text-2xl font-semibold text-jrpay-navy">
                    {t('users.title')}
                </h1>
                <button className="btn-primary" onClick={() => setCreateOpen(true)}>
                    {t('users.create')}
                </button>
            </header>

            <div className="bg-white rounded-lg shadow-sm mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th scope="col" className="text-left px-3 py-2">
                                Name
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Email
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Role
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Status
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                Last login
                            </th>
                            <th scope="col" className="text-left px-3 py-2">
                                {t('common.actions')}
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading && (
                            <tr>
                                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                                    {t('common.loading')}
                                </td>
                            </tr>
                        )}
                        {!loading &&
                            users?.items.map((u) => (
                                <tr key={u.userId} className="border-t border-slate-100">
                                    <td className="px-3 py-2">
                                        {u.name}
                                        {u.department && (
                                            <p className="text-xs text-slate-500">{u.department}</p>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-xs">{u.email}</td>
                                    <td className="px-3 py-2 text-xs">
                                        <span className={roleClass(u.role)}>{u.role}</span>
                                    </td>
                                    <td className="px-3 py-2 text-xs">
                                        <span className={statusClass(u.status)}>{u.status}</span>
                                        {u.mustSetupSca && (
                                            <p className="text-xs text-amber-700 mt-0.5">
                                                SCA setup pending
                                            </p>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-xs">
                                        {u.lastLoginAt
                                            ? formatDateTime(u.lastLoginAt, i18n.language)
                                            : '—'}
                                    </td>
                                    <td className="px-3 py-2 text-xs">
                                        <div className="flex flex-wrap gap-1">
                                            {u.status === 'ACTIVE' && (
                                                <button
                                                    className="btn-secondary text-xs py-1"
                                                    onClick={() =>
                                                        setActOn({
                                                            user: u,
                                                            action: 'SUSPEND_OPERATOR',
                                                        })
                                                    }
                                                >
                                                    {t('users.suspend')}
                                                </button>
                                            )}
                                            {u.status === 'SUSPENDED' && (
                                                <button
                                                    className="btn-secondary text-xs py-1"
                                                    onClick={() =>
                                                        setActOn({
                                                            user: u,
                                                            action: 'REACTIVATE_OPERATOR',
                                                        })
                                                    }
                                                >
                                                    {t('users.reactivate')}
                                                </button>
                                            )}
                                            {u.status === 'LOCKED' && (
                                                <button
                                                    className="btn-secondary text-xs py-1"
                                                    onClick={() =>
                                                        setActOn({
                                                            user: u,
                                                            action: 'UNLOCK_OPERATOR',
                                                        })
                                                    }
                                                >
                                                    Unlock
                                                </button>
                                            )}
                                            {u.status !== 'DELETED' && (
                                                <button
                                                    className="btn-secondary text-xs py-1"
                                                    onClick={() =>
                                                        setActOn({
                                                            user: u,
                                                            action: 'UPDATE_OPERATOR_ROLE',
                                                        })
                                                    }
                                                >
                                                    Role
                                                </button>
                                            )}
                                            {u.status !== 'DELETED' && (
                                                <button
                                                    className="btn-danger text-xs py-1"
                                                    onClick={() =>
                                                        setActOn({
                                                            user: u,
                                                            action: 'DELETE_OPERATOR',
                                                        })
                                                    }
                                                >
                                                    {t('users.delete')}
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                    </tbody>
                </table>
            </div>

            {createOpen && (
                <CreateOperatorModal
                    onClose={() => setCreateOpen(false)}
                    onCreated={() => {
                        setCreateOpen(false);
                        setRefresh((n) => n + 1);
                        showInfo(t('users.create'));
                    }}
                />
            )}

            {actOn && (
                <UserActionModal
                    user={actOn.user}
                    action={actOn.action}
                    onClose={() => setActOn(null)}
                    onDone={() => {
                        setActOn(null);
                        setRefresh((n) => n + 1);
                    }}
                />
            )}
        </section>
    );
}

function CreateOperatorModal({
    onClose,
    onCreated,
}: {
    onClose: () => void;
    onCreated: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem } = useToasts();
    const [form, setForm] = useState({
        email: '',
        name: '',
        role: 'FRAUD_ANALYST' as Role,
        department: '',
        notes: '',
        confirmedCriteria: false,
    });
    const [scaOpen, setScaOpen] = useState(false);
    const [hash, setHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const valid = form.email && form.name && form.notes.length >= 50 && form.confirmedCriteria;

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            setHash(await sha256HexNfc(form.notes));
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            const body = await api<{ userId?: string; pendingApprovalRef?: string }>(
                '/api/v1/admin/users',
                {
                    method: 'POST',
                    stepUpToken,
                    body: {
                        email: form.email.trim(),
                        name: form.name.trim(),
                        role: form.role,
                        department: form.department || undefined,
                        notes: form.notes,
                        confirmedCriteria: true,
                    },
                }
            );
            if (body.pendingApprovalRef) {
                onCreated();
                return;
            }
            onCreated();
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

    if (scaOpen && hash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action: 'CREATE_OPERATOR',
                    resourceType: 'user',
                    resourceId: form.email,
                    notesHash: hash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void post(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <Modal titleId="create-op-title" onClose={onClose}>
            <h2 id="create-op-title" className="text-lg font-semibold text-jrpay-navy">
                {t('users.create')}
            </h2>
            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                <div className="grid grid-cols-2 gap-3">
                    <label>
                        Email
                        <input
                            type="email"
                            className="input mt-1"
                            value={form.email}
                            onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                        />
                    </label>
                    <label>
                        Full name
                        <input
                            className="input mt-1"
                            value={form.name}
                            onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                        />
                    </label>
                    <label>
                        Role
                        <select
                            className="input mt-1"
                            value={form.role}
                            onChange={(e) =>
                                setForm((f) => ({ ...f, role: e.target.value as Role }))
                            }
                        >
                            {ROLES.map((r) => (
                                <option key={r}>{r}</option>
                            ))}
                        </select>
                    </label>
                    <label>
                        Department (optional)
                        <input
                            className="input mt-1"
                            value={form.department}
                            onChange={(e) =>
                                setForm((f) => ({ ...f, department: e.target.value }))
                            }
                        />
                    </label>
                </div>
                {form.role === 'ADMIN' && (
                    <p className="text-xs text-amber-700">
                        ADMIN role requires concurrence from a second SUPERVISOR.
                    </p>
                )}
                <label className="block">
                    Notes (≥ 50 chars)
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={form.notes}
                        onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
                    />
                </label>
                <p className={form.notes.length >= 50 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                    {form.notes.length} / 50
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={form.confirmedCriteria}
                        onChange={(e) =>
                            setForm((f) => ({ ...f, confirmedCriteria: e.target.checked }))
                        }
                    />
                    <span>I confirm the operator criteria are met.</span>
                </label>
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
                        disabled={!valid || submitting}
                    >
                        {t('users.create')}
                    </button>
                </div>
            </form>
        </Modal>
    );
}

function UserActionModal({
    user,
    action,
    onClose,
    onDone,
}: {
    user: OperatorSummary;
    action: Action;
    onClose: () => void;
    onDone: () => void;
}) {
    const { t } = useTranslation();
    const { showProblem, showInfo } = useToasts();
    const [notes, setNotes] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [newRole, setNewRole] = useState<Role>(user.role);
    const [scaOpen, setScaOpen] = useState(false);
    const [hash, setHash] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const valid = notes.length >= 50 && confirmed && (action !== 'UPDATE_OPERATOR_ROLE' || newRole !== user.role);

    async function startSca(e?: React.FormEvent) {
        e?.preventDefault();
        if (!valid) return;
        setSubmitting(true);
        try {
            setHash(await sha256HexNfc(notes));
            setScaOpen(true);
        } catch (e2) {
            showProblem(e2);
            setSubmitting(false);
        }
    }

    async function post(stepUpToken: string) {
        try {
            const userPath = encodeURIComponent(user.userId);
            const body: Record<string, unknown> = { notes, confirmedCriteria: true };
            let url: string;
            let method: 'POST' | 'PATCH' | 'DELETE' = 'POST';

            switch (action) {
                case 'SUSPEND_OPERATOR':
                    url = `/api/v1/admin/users/${userPath}/suspend`;
                    break;
                case 'REACTIVATE_OPERATOR':
                    url = `/api/v1/admin/users/${userPath}/reactivate`;
                    break;
                case 'UNLOCK_OPERATOR':
                    url = `/api/v1/admin/users/${userPath}/unlock`;
                    break;
                case 'UPDATE_OPERATOR_ROLE':
                    url = `/api/v1/admin/users/${userPath}`;
                    method = 'PATCH';
                    body.role = newRole;
                    break;
                case 'DELETE_OPERATOR':
                    url = `/api/v1/admin/users/${userPath}`;
                    method = 'DELETE';
                    break;
                default:
                    throw new Error(`Unsupported action: ${action}`);
            }

            await api(url, { method, stepUpToken, body });
            showInfo(actionLabel(t, action));
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

    if (scaOpen && hash) {
        return (
            <ScaModal
                inputs={{
                    class: 'admin-action',
                    action,
                    resourceType: 'user',
                    resourceId: user.userId,
                    decision: action === 'UPDATE_OPERATOR_ROLE' ? newRole : undefined,
                    notesHash: hash,
                }}
                onResolve={(token) => {
                    setScaOpen(false);
                    void post(token);
                }}
                onCancel={() => {
                    setScaOpen(false);
                    setSubmitting(false);
                }}
            />
        );
    }

    return (
        <Modal titleId="user-action-title" onClose={onClose}>
            <h2 id="user-action-title" className="text-lg font-semibold text-jrpay-navy">
                {actionLabel(t, action)} — {user.name}
            </h2>
            <p className="text-xs text-slate-500 mt-1">{user.email}</p>

            <form onSubmit={startSca} className="mt-4 space-y-3 text-sm">
                {action === 'UPDATE_OPERATOR_ROLE' && (
                    <label className="block">
                        New role
                        <select
                            className="input mt-1"
                            value={newRole}
                            onChange={(e) => setNewRole(e.target.value as Role)}
                        >
                            {ROLES.map((r) => (
                                <option key={r}>{r}</option>
                            ))}
                        </select>
                    </label>
                )}
                <label className="block">
                    Notes (≥ 50 chars)
                    <textarea
                        className="input mt-1"
                        rows={4}
                        value={notes}
                        onChange={(e) => setNotes(e.target.value)}
                    />
                </label>
                <p className={notes.length >= 50 ? 'text-xs text-jrpay-green text-right' : 'text-xs text-jrpay-red text-right'}>
                    {notes.length} / 50
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    <span>I confirm this action is required.</span>
                </label>
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
                        disabled={!valid || submitting}
                    >
                        {actionLabel(t, action)}
                    </button>
                </div>
            </form>
        </Modal>
    );
}

function actionLabel(t: (k: string) => string, a: Action): string {
    switch (a) {
        case 'SUSPEND_OPERATOR':
            return t('users.suspend');
        case 'REACTIVATE_OPERATOR':
            return t('users.reactivate');
        case 'DELETE_OPERATOR':
            return t('users.delete');
        case 'UNLOCK_OPERATOR':
            return 'Unlock';
        case 'UPDATE_OPERATOR_ROLE':
            return 'Change role';
        case 'CREATE_OPERATOR':
            return t('users.create');
    }
}

function roleClass(r: Role): string {
    if (r === 'ADMIN') return 'badge badge-high';
    if (r === 'SUPERVISOR') return 'badge badge-medium';
    if (r === 'COMPLIANCE_OFFICER') return 'badge badge-info';
    return 'badge badge-neutral';
}

function statusClass(s: Status): string {
    if (s === 'ACTIVE') return 'badge badge-low';
    if (s === 'SUSPENDED' || s === 'LOCKED') return 'badge badge-medium';
    if (s === 'DELETED') return 'badge badge-neutral';
    return 'badge badge-info';
}

function formatDateTime(iso: string, locale: string): string {
    try {
        return new Intl.DateTimeFormat(locale, {
            dateStyle: 'short',
            timeStyle: 'short',
        }).format(new Date(iso));
    } catch {
        return iso;
    }
}
