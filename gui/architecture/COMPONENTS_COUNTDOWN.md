# Countdown Component — Canonical Pattern

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §5.3 (web components) and §6 (mobile components).*
*Phase 1 finding reference: FA.4, FA.1.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Purpose

Many UCs display a countdown — SCA remaining time (5 min), session idle warning (60 s), PII-reveal auto-remask (60 s), R2P due date, fraud SLA (1 h), sanctions SLA (15 min), stream-token expiry, signed-URL expiry. Implemented naively with `aria-live="polite"` on the seconds counter, a screen reader announces every second — unusable.

This document specifies a single canonical component pattern used everywhere a countdown is shown.

---

## Behaviour contract

1. **Visible seconds update every second.** Standard UI update.
2. **Screen-reader announcements fire only at thresholds.** Default thresholds for a 5-minute countdown: `4m00s, 3m00s, 2m00s, 1m00s, 30s, 15s, 0s`. For a 60-s countdown: `30s, 15s, 5s, 0s`. For a 1-h countdown: `30m, 15m, 10m, 5m, 1m, 0s`. Thresholds are parameterisable by the consuming UC.
3. **Respects `prefers-reduced-motion`.** No tick animation when reduced motion is on.
4. **Announces on initial mount** ("5 minutes remaining" for SCA). Subsequent announcements only at thresholds.
5. **Supports extension.** The component exposes a "Extend" action when the caller provides an `onExtend` handler; wired to UC-G36 60-s reveal extension (FA.1 fix) and UC-G52 session warning "Stay signed in" button.
6. **Locale-aware.** Announces "5 minutter igjen" in `nb-NO`, "5 minutes remaining" in `en-US`. Numbers pluralise via ICU MessageFormat.
7. **On expiry.** Fires `onExpire` callback; component re-renders in expired state with a distinct visual treatment.

## Web (admin-web + client-web) implementation sketch

```tsx
<Countdown
  totalSeconds={300}
  thresholds={[240, 180, 120, 60, 30, 15, 0]}
  onExpire={handleExpire}
  onExtend={handleExtend}              // optional; shows Extend button when provided
  labelKey="sca.countdown.label"       // i18n key parameterised with {remaining}
  announceKey="sca.countdown.announce" // i18n key for threshold announcements
/>
```

Internally:
- Visible `<span>` shows `mm:ss` (or `h:mm:ss`) updating each second via React state tied to a 1-second interval.
- Announcement `<span role="status" aria-live="polite" aria-atomic="true">` is hidden visually and only mutated when the counter hits a threshold.
- `useEffect` cleanup clears the interval on unmount.

## iOS implementation sketch

```swift
CountdownView(
    totalSeconds: 300,
    thresholds: [240, 180, 120, 60, 30, 15, 0],
    onExpire: handleExpire,
    onExtend: onExtendOptional,
    labelKey: "sca.countdown.label",
    announceKey: "sca.countdown.announce"
)
```

Internals:
- `@State` for the remaining seconds + timer publishing each second.
- Visible `Text` updates from state.
- `UIAccessibility.post(notification: .announcement, argument: ...)` triggered only when `remaining` crosses a threshold boundary.
- Respects `UIAccessibility.isReduceMotionEnabled`.
- Handles background/foreground transitions: on background, suspends the timer; on foreground, resumes with corrected `remaining = startAt + totalSeconds - now`.

## Android (Compose) implementation sketch

```kotlin
Countdown(
    totalSeconds = 300,
    thresholds = listOf(240, 180, 120, 60, 30, 15, 0),
    onExpire = ::handleExpire,
    onExtend = onExtendOptional,
    labelKey = "sca.countdown.label",
    announceKey = "sca.countdown.announce",
)
```

Internals:
- `LaunchedEffect` ticks every second.
- Visible `Text`.
- Accessibility announcement via `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` applied to a hidden `Text` that mutates only at thresholds.
- Respects `Settings.Global.TRANSITION_ANIMATION_SCALE`.

## i18n keys

Every UC using the component references:

- `<feature>.countdown.label` — visible label; ICU parameter `{m, number} {m, plural, one{minutt} other{minutter}} {s, number} {s, plural, one{sekund} other{sekunder}}`. Example: `sca.countdown.label`: `"{m, number} {m, plural, one{minutt} other{minutter}} og {s, number} {s, plural, one{sekund} other{sekunder}} igjen"`.
- `<feature>.countdown.announce` — screen-reader announcement at threshold. Example: `"4 minutter igjen"`.
- `<feature>.countdown.expired` — expired state. Example: `sca.countdown.expired`: `"Tiden er ute."`.

## Coverage per UC (enforcement)

Each UC that has a countdown must:
1. Use the `Countdown` component, not a hand-rolled implementation.
2. Declare its `thresholds` explicitly.
3. Provide an `onExtend` handler if WCAG 2.2.1 extension is needed (PII reveal UC-G36; session UC-G52).
4. Declare its WCAG 2.2.1 compliance clause in §12 UX notes:
   - PII reveal (UC-G36): 2.2.1 clause (c) — user is warned ≥ 20 s and can extend.
   - SCA challenge (UC-G05, UC-G51): clause (d) — essential per PSD2 RTS Art. 5.
   - Session idle (UC-G52): clause (c) — warning + extension.
   - Stream-token (UC-G05 step 13): clause (d) — essential; network-layer TTL.
   - Signed-URL (UC-G04, UC-G24): clause (a) — re-request always possible.
   - Fraud / sanctions SLA: clauses (a) operator-facing; internal UX, less strict.

## Testing

Per `gui_requirements.md` §2.8 (updated to include FA.2 focus discipline):

- **Unit:** the `Countdown` component's threshold calculation, locale formatting, and accessibility-announcement trigger logic.
- **Component:** renders as expected; announces on threshold crossings; doesn't announce between them.
- **E2E:** every UC with a countdown has a scripted test verifying the announce/extend UX.

## Performance note

A 5-minute countdown with a 1-second interval fires 300 times; trivial. No React `memo` needed. On iOS/Android, the timer should suspend on background to save battery — documented above.
