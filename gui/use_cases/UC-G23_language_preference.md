# UC-G23 — Language Preference

*Owner: Frontend / i18n*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer sets their preferred language (Norwegian Bokmål or English in MVP). The preference is server-persisted so every device automatically renders in the same language and so server-originated notifications (email, SMS, push) use the correct locale.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** Profile service (preferences store), notification service (consumes locale for composition)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin language is per-operator in UC-G43 |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated.

## 5. Postconditions

- `profile.language` updated.
- Subsequent UI renders in the new locale (after a refresh / re-mount, which happens automatically on save).
- Subsequent notifications use the new locale.
- Audit event `profile.language.updated`.

## 6. Main flow (happy path)

1. Customer navigates to **Innstillinger** → **Språk / Language**.
2. GUI fetches current language (already loaded with profile in UC-G22).
3. Radio list:
   - Norsk bokmål (nb-NO) — default
   - English (en-US)
4. Customer selects.
5. GUI calls `PATCH /api/v1/customer/profile` with `{language: "en-US"}`.
6. No SCA (low-risk change).
7. On success, GUI invalidates the i18n catalogue query, reloads the active locale resource (web: dynamic import of `en-US` bundle; mobile: swaps the in-memory `Bundle` / `ResourceBundle`), and re-renders all screens.
8. Notification service observes the change for future notifications.

## 7. Alternative flows

### AF-01 — Customer's device OS locale differs from preference

1. The server preference wins on web (app language) and mobile (in-app language).
2. System-level things — e.g., Siri, Bixby, OS share sheet strings — remain in the OS locale.

### AF-02 — Locale fallback for missing strings

1. Missing string in `en-US` catalogue falls back to `nb-NO` (source of truth). A CI lint fails the build if any key is missing from `en-US`, so this should be theoretical.

### AF-03 — Customer chooses language during first-time onboarding

1. First login presents this screen as step 1 if `language` is unset (upstream CDD may or may not set it).

### AF-04 — Right-to-left language requested (future)

1. Not supported in MVP per GUI-I18N-05. Picker does not offer RTL options.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on PATCH | Response | Revert selection; toast "Kunne ikke lagre — prøv igjen" |
| i18n bundle load fails after save | Catalogue fetch | Keep UI in previous language; show reload prompt |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F91`, `GUI-I18N-01..05`
- `system_requirements.md` — GDPR Art. 12 (clear and plain language), Finansavtaleloven §3-1

## 10. API operations involved

- `PATCH /api/v1/customer/profile` (reuses customer-profile.yaml)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../i18n/__tests__/language-picker.test.tsx`, `catalogue-swap.test.ts` | `.../Tests/LanguagePickerTests.swift` | `.../i18n/LanguagePickerTest.kt` |
| Component | `.../i18n/__tests__/language-settings-screen.test.tsx` | `.../Tests/LanguageSettingsViewTests.swift` | `.../LanguageSettingsScreenTest.kt` |
| Contract | shares `.../profile.pact` | (same) | (same) |
| E2E | `client-web/e2e/language-change.spec.ts` | `.../UITests/LanguageChangeUITests.swift` | `.../androidTest/.../LanguageChangeE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/language_change_en.json`, `language_catalogue_parity.json` | (same) | (same) |

## 12. UX notes

- The preview region at the bottom of the picker shows a sample of the new language ("Slik ser dette ut på norsk" / "This is how it looks in English") so the customer sees what they're about to get.
- Currency and date formatting is coupled: `nb-NO` → `1 234,50` / `23. april 2026`; `en-US` → `1,234.50` / `April 23, 2026`. This is structural; not a separate setting.

## 13. Open questions

- Whether to add Nynorsk (`nn-NO`), Sami, or Swedish/Danish. *Defer — add as catalogue completions; not MVP-gating.*
