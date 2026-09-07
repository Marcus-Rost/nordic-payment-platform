# UC-G43 — User and Role Administration

*Owner: Frontend / Admin / Identity*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

SUPERVISOR operators manage back-office users: list, create, assign roles, suspend/reactivate/delete, unlock (after lockout), and view per-user activity log. All actions require SCA and are audited; ADMIN role can see everyone including themselves (viewing their own suspend action requires a peer or higher to execute it).

## 2. Actors

- **Primary:** SUPERVISOR, ADMIN
- **Secondary:** Identity service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `users:manage`.

## 5. Postconditions

- User record created/updated/suspended/deleted.
- Audit `operator.<created|updated|suspended|reactivated|deleted|unlocked>`.

## 6. Main flow (happy path)

### 6.1 List

1. Operator navigates to **Admin** → **Brukere / Users**.
2. GUI fetches `GET /api/v1/admin/users`.
3. Row per user: name, username (email), role, last login, status (ACTIVE / SUSPENDED / LOCKED / DELETED).
4. Filters: role, department, status, last-login-age.

### 6.2 Create

1. Click **Ny bruker / New user**.
2. Form: name, email, department (free-text), initial role (dropdown from the locked set: FRAUD_ANALYST / COMPLIANCE_OFFICER / OPERATIONS / SUPERVISOR / ADMIN).
3. Step-up SCA (dynamic-linking `{newUserEmail, role}`).
4. Backend `POST /api/v1/admin/users` — creates user with a temporary password emailed to the new user and `mustSetupSca=true`.
5. UI confirms; new user appears in the list as PENDING_FIRST_LOGIN.

### 6.3 Change role

1. Operator opens user detail → clicks **Endre rolle / Change role**.
2. Modal: new role dropdown; notes ≥ 20 chars.
3. Step-up SCA.
4. Backend `PATCH /api/v1/admin/users/{id}` with `{role}`.

### 6.4 Suspend / Reactivate / Delete

1. Each action has a modal with notes ≥ 20 chars and step-up SCA.
2. `POST /api/v1/admin/users/{id}/suspend`
3. `POST /api/v1/admin/users/{id}/reactivate`
4. `DELETE /api/v1/admin/users/{id}` — soft delete (archive); past audit entries remain.

### 6.5 Unlock (after lockout — IDS-F07)

1. User shows LOCKED status (reached the 5-in-24h limit).
2. Operator clicks **Lås opp / Unlock** → SCA → backend clears the lockout counter.

### 6.6 Activity log per user (ADM-F93)

1. User detail has a "Aktivitet / Activity" tab.
2. Shows last 90 days of this operator's actions: decisions made, accounts touched, PII reveals (date, customer hash, field).
3. Filters + export to CSV.

## 7. Alternative flows

### AF-01 — SUPERVISOR attempts to delete themselves

1. UI rejects; tooltip explains "Du kan ikke slette din egen bruker."

### AF-02 — Email conflict on create

1. 409; copy explains "En bruker med denne e-posten finnes allerede."

### AF-03 — Single-SUPERVISOR risk (only one SUPERVISOR in the system)

1. UI warns when demoting / suspending / deleting a SUPERVISOR if only one other SUPERVISOR remains; double-SCA confirmation required to proceed.

### AF-04 — ADMIN role assignment requires two SUPERVISORs

1. Locked as a policy in backend: creating or promoting to ADMIN returns 202 with `pendingSecondApproval=true` and notifies a second SUPERVISOR to approve. UI reflects the pending state.

### AF-05 — Role change of a user with active assignments (e.g., owns fraud cases)

1. Warning: "Denne brukeren har 3 åpne svindelsaker. Endring av rollen kan fjerne tilgangen." Operator must confirm.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on mutate | Response | Retryable with idempotency key |
| SCA fails | UC-G51 | No change |
| Email adapter fails (invite) | Adapter | Show retry button; user still created with `emailInviteFailed=true` flag |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F90..F93`, `GUI-SEC-14`, `GUI-SEC-15`
- `system_requirements.md` — IDS-F06 (RBAC)
- `PROJECT_DECISIONS.md` — D2

## 10. API operations involved

- `GET    /api/v1/admin/users` (`admin-users.yaml`)
- `POST   /api/v1/admin/users`
- `GET    /api/v1/admin/users/{id}`
- `PATCH  /api/v1/admin/users/{id}`
- `DELETE /api/v1/admin/users/{id}`
- `POST   /api/v1/admin/users/{id}/suspend` / `reactivate` / `unlock`
- `GET    /api/v1/admin/users/{id}/activity`

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../users/__tests__/user-row.test.tsx`, `user-create-form.test.tsx`, `role-change-modal.test.tsx` |
| Component | `admin-web/.../users/__tests__/user-list.test.tsx`, `user-detail.test.tsx` |
| Contract | `admin-web/.../admin-users.pact.test.ts` |
| E2E | `admin-web/e2e/admin-user-create.spec.ts`, `admin-user-role-change.spec.ts`, `admin-user-suspend.spec.ts`, `admin-user-unlock.spec.ts`, `admin-user-two-person-admin-promotion.spec.ts` |

## 12. UX notes

- Role dropdown shows a one-line description per role (scope-bundle summary) so SUPERVISORs don't need to look up the mapping.
- Destructive actions (delete) have red styling; the "ADMIN" option in the role dropdown has an extra warning label "Brukes kun for nødsrettstilgang / Break-glass access only".
- The activity tab respects UC-G36 — PII reveals are listed with the affected customer hashed; clicking through requires the PII-reveal right itself (full round-trip auditing).

## 13. Open questions

- Whether to support team hierarchies (manager relationships between operators). *Defer — flat RBAC is simpler and sufficient for the MVP.*
