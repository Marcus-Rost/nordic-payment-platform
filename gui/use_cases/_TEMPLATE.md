# UC-G<nn> — <Use Case Name>

*Owner: <feature owner>*
*Status: DRAFT | REVIEWED | IMPLEMENTED | VERIFIED*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: YYYY-MM-DD*

---

## 1. Purpose

One paragraph describing what this use case lets the user accomplish and why.

## 2. Actors

- **Primary actor:** (e.g., bank customer, fraud analyst)
- **Secondary actors:** (e.g., A4 PSD2 service, A6 VoP service)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web (G1) | yes/no | |
| Client Web (G2) | yes/no | |
| Client iOS (G3) | yes/no | |
| Client Android (G4) | yes/no | |

## 4. Preconditions

- (e.g., User is authenticated; user holds `payments:initiate` scope.)

## 5. Postconditions

- (e.g., A new payment exists in A2 in state SETTLED; an audit event has been emitted.)

## 6. Main flow (happy path)

1. ...
2. ...
3. ...

## 7. Alternative flows

### AF-01 — <name>

1. ...

### AF-02 — <name>

1. ...

## 8. Failure modes

- (Concrete failures the GUI must handle, with the canonical error code from the API contract.)

## 9. Related requirements

- `gui/requirements/gui_requirements.md` — `<REQ-ID>`, `<REQ-ID>` ...

## 10. API operations involved

- `<METHOD>` `<path>` (`implementation/api-specs/<file>.yaml`)
- ...

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | path | path | path |
| Component | path | path | path |
| Contract (Pact) | path | path | path |
| E2E | path | path | path |
| Cross-platform consistency | `implementation/cross-platform-tests/scenarios/<file>.json` | (same) | (same) |

## 12. UX notes

(Anything that's not strictly a requirement but matters: copy nuance, motion, microinteraction, accessibility callouts.)

## 13. Open questions

(Anything still under review — to be resolved in Phase-1 review.)
