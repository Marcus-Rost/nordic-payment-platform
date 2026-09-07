# GUI Use Case Index

*Naming: `UC-G<nn>_<slug>.md` — distinguished from backend `UC<nn>_<slug>.md` by the `G` prefix.*
*Status legend: D = Draft, R = Reviewed, I = Implemented, V = Verified.*

**Numbering bands:** 01–29 customer narratives; 30–43 admin narratives; 44–49 overflow / new additions discovered in review; 50–54 cross-cutting; 80+ reserved for shared platform UCs (e.g., notification inbox browse).

51 use cases are drafted as of 2026-04-24 (48 from Phase 0 + 3 added in Phase 1 Bucket A: **UC-G44 Password Reset** for CLI-F05 gap; **UC-G45 Privacy Notice Disclosure** for GDPR Art. 13/14 gap; **UC-G80 Browse Notification Inbox** for CLI-F80 canonical). Phase 1 reviews completed; fixes applied per `gui/reviews/FIXES_APPLIED.md`.

---

## Client narratives (apply identically to G2/G3/G4)

| ID | Title | Status | Web | iOS | Android | Backing reqs |
|----|-------|--------|-----|-----|---------|--------------|
| [UC-G01](UC-G01_login_session_lifecycle.md) | Login and session lifecycle | D | ☐ | ☐ | ☐ | CLI-F01..F05 |
| [UC-G02](UC-G02_account_list_and_balances.md) | Account list and balances | D | ☐ | ☐ | ☐ | CLI-F10..F11 |
| [UC-G03](UC-G03_transaction_history_and_detail.md) | Transaction history and detail | D | ☐ | ☐ | ☐ | CLI-F11..F12 |
| [UC-G04](UC-G04_account_statement_export.md) | Account statement export (PDF, JSON, camt.053) | D | ☐ | ☐ | ☐ | CLI-F13 |
| [UC-G05](UC-G05_initiate_instant_payment_with_vop.md) | Initiate one-off instant payment with VoP | D | ☐ | ☐ | ☐ | CLI-F20..F28 |
| [UC-G06](UC-G06_initiate_batch_payment.md) | Initiate one-off batch payment | D | ☐ | ☐ | ☐ | CLI-F20..F28 |
| [UC-G07](UC-G07_schedule_future_dated_payment.md) | Schedule a future-dated payment | D | ☐ | ☐ | ☐ | CLI-F40 |
| [UC-G08](UC-G08_setup_avtalegiro_mandate.md) | Set up an AvtaleGiro mandate | D | ☐ | ☐ | ☐ | CLI-F41 |
| [UC-G09](UC-G09_manage_existing_mandates.md) | Manage existing mandates (modify, revoke) | D | ☐ | ☐ | ☐ | CLI-F42 |
| [UC-G10](UC-G10_setup_vrp_consent.md) | Set up a VRP consent | D | ☐ | ☐ | ☐ | CLI-F43..F44 |
| [UC-G11](UC-G11_receive_and_accept_r2p.md) | Receive and act on an R2P (accept now) | D | ☐ | ☐ | ☐ | CLI-F50..F52 |
| [UC-G12](UC-G12_decline_r2p.md) | Decline an R2P | D | ☐ | ☐ | ☐ | CLI-F53 |
| [UC-G13](UC-G13_defer_r2p.md) | Defer an R2P | D | ☐ | ☐ | ☐ | CLI-F51 |
| [UC-G14](UC-G14_request_amendment_r2p.md) | Request amendment to an R2P | D | ☐ | ☐ | ☐ | CLI-F51 |
| [UC-G15](UC-G15_qr_code_payment_initiation.md) | QR-code payment initiation | D | ☐ | ☐ | ☐ | CLI-F54 |
| [UC-G16](UC-G16_manage_trusted_beneficiaries.md) | Manage trusted beneficiaries | D | ☐ | ☐ | ☐ | CLI-F60..F62 |
| [UC-G17](UC-G17_grant_psd2_consent_tpp.md) | Grant a PSD2 consent (TPP-initiated redirect) | D | ☐ | ☐ | ☐ | CLI-F70..F72 |
| [UC-G18](UC-G18_manage_active_consents.md) | Manage / revoke active consents | D | ☐ | ☐ | ☐ | CLI-F70..F71 |
| [UC-G19](UC-G19_fraud_hold_notification.md) | Receive a fraud-hold notification | D | ☐ | ☐ | ☐ | CLI-F80, F83 |
| [UC-G20](UC-G20_sanctions_block_notification.md) | Receive a sanctions-block notification | D | ☐ | ☐ | ☐ | CLI-F80, F84 |
| [UC-G21](UC-G21_notification_preferences.md) | Notification preferences | D | ☐ | ☐ | ☐ | CLI-F81 |
| [UC-G22](UC-G22_profile_view_and_edit.md) | Profile view and edit | D | ☐ | ☐ | ☐ | CLI-F90 |
| [UC-G23](UC-G23_language_preference.md) | Language preference | D | ☐ | ☐ | ☐ | CLI-F91 |
| [UC-G24](UC-G24_gdpr_data_export.md) | GDPR data export | D | ☐ | ☐ | ☐ | CLI-F92 |
| [UC-G25](UC-G25_gdpr_account_erasure.md) | GDPR account erasure | D | ☐ | ☐ | ☐ | CLI-F93 |
| [UC-G26](UC-G26_consent_log_review.md) | Consent log review | D | ☐ | ☐ | ☐ | CLI-F94 |
| [UC-G27](UC-G27_mobile_cold_start_biometric_unlock.md) | Mobile cold-start biometric unlock | D | n/a | ☐ | ☐ | CLI-F04 |
| [UC-G28](UC-G28_offline_read_cached_data.md) | Offline read of cached account / transactions | D | n/a | ☐ | ☐ | CLI-NF07 |
| [UC-G29](UC-G29_push_notification_deep_link.md) | Push-notification deep link to deep-linked target | D | n/a | ☐ | ☐ | CLI-F82 |

## Admin narratives (G1 only)

| ID | Title | Status | Backing reqs |
|----|-------|--------|--------------|
| [UC-G30](UC-G30_operator_login_with_sca.md) | Operator login with SCA | D | ADM-F01..F03 |
| [UC-G31](UC-G31_fraud_case_triage.md) | Fraud case queue and triage | D | ADM-F10..F15 |
| [UC-G32](UC-G32_fraud_case_decision_confirmed.md) | Fraud case decision (CONFIRMED_FRAUD with freeze) | D | ADM-F13 |
| [UC-G33](UC-G33_fraud_case_decision_dismissed.md) | Fraud case decision (DISMISSED with release) | D | ADM-F13 |
| [UC-G34](UC-G34_sanctions_hit_review.md) | Sanctions hit queue and review | D | ADM-F20..F23 |
| [UC-G35](UC-G35_account_search_freeze_unfreeze_close.md) | Account search, freeze, unfreeze, close | D | ADM-F30..F36 |
| [UC-G36](UC-G36_pii_reveal_with_audit.md) | PII reveal with audit | D | ADM-F32, GUI-SEC-15 |
| [UC-G37](UC-G37_stuck_payment_investigation.md) | Stuck payment investigation (pacs.028) | D | ADM-F40..F41 |
| [UC-G38](UC-G38_payment_return_pacs004.md) | Payment return (pacs.004) | D | ADM-F42 |
| [UC-G39](UC-G39_reconciliation_status_and_run.md) | Reconciliation status and run | D | ADM-F50..F53 |
| [UC-G40](UC-G40_audit_log_search_and_hash_chain_verification.md) | Audit log search and hash-chain verification | D | ADM-F60..F62 |
| [UC-G41](UC-G41_gdpr_queue_processing.md) | GDPR queue processing | D | ADM-F70..F73 |
| [UC-G42](UC-G42_system_health_and_sla_dashboard.md) | System health and SLA dashboard | D | ADM-F80..F84 |
| [UC-G43](UC-G43_user_and_role_administration.md) | User and role administration | D | ADM-F90..F93 |

## Overflow (added in Phase 1 Bucket A)

| ID | Title | Status | Backing reqs |
|----|-------|--------|--------------|
| [UC-G44](UC-G44_password_reset.md) | Password reset (customer + operator) | D | CLI-F05, IDS-F14..F17 |
| [UC-G45](UC-G45_privacy_notice_disclosure.md) | Privacy notice disclosure (Art. 13/14) | D | GUI-PRV-07 |

## Cross-cutting (apply to both audiences)

| ID | Title | Status | Backing reqs |
|----|-------|--------|--------------|
| [UC-G50](UC-G50_oauth2_authorization_code_pkce.md) | OAuth2 authorization-code flow with PKCE | D | IDS-F01..F03 |
| [UC-G51](UC-G51_dynamic_linking_sca.md) | Dynamic-linking SCA flow | D | IDS-F05, GUI-SEC-08 |
| [UC-G52](UC-G52_session_expiry_warning_and_reauth.md) | Session expiry warning and re-auth | D | GUI-SEC-10 |
| [UC-G53](UC-G53_session_revocation_multi_device_logout.md) | Session revocation across devices on logout | D | GUI-SEC-17 |
| [UC-G54](UC-G54_status_banner_display.md) | Status banner display (maintenance / incident) | D | ADM-F84, CLI-F80 |

## Shared platform surfaces

| ID | Title | Status | Backing reqs |
|----|-------|--------|--------------|
| [UC-G80](UC-G80_notification_inbox_browse.md) | Browse notification inbox | D | CLI-F80 |
