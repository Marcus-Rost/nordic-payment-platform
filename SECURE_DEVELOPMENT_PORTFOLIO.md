# Why This Project Demonstrates Secure Application Development Expertise

This is a payment processing system built with security as a structural
property, not a feature bolted on at the end. Six microservices, shared
security infrastructure, systematic threat-informed reviews, and a test
framework that verifies security at every layer.

Here is what that demonstrates, and why it matters for roles in banking
software, fintech, or any domain where security failures have real
consequences.

---

## 1. Security is enforced by default, not configured per service

The `jrpay-security-starter` is a shared Spring Boot starter that every
service depends on. It provides:

- **Deny-all default.** Every endpoint is blocked unless explicitly
  permitted. Services opt in to access, not opt out of restrictions.
  A new endpoint that forgets to configure security is secure by default —
  it returns 403, not 200.

- **OWASP security headers on every response.** Content-Security-Policy,
  X-Content-Type-Options, X-Frame-Options, Strict-Transport-Security,
  Cache-Control for sensitive data. These are not configured per controller —
  they are applied automatically by the starter.

- **RFC 9457 Problem Details for all error responses.** No stack traces, no
  internal class names, no database error messages leak to the client.
  Errors return a structured JSON body with a correlation ID for internal
  debugging. This is enforced by a global exception handler in the starter,
  not by individual try-catch blocks.

**Why this matters:** In most projects, security configuration is scattered
across services and each team configures it differently. The shared starter
pattern ensures security policy is defined once, tested once, and inherited
everywhere. A new service gets the full security posture by adding a single
Gradle dependency.

---

## 2. Authentication is never disabled — not even for testing

Many projects disable security in test and demo environments for
convenience. This project does not. Instead, it uses profile-activated
`DemoSecurityConfig` classes that provide HTTP Basic auth with in-memory
test users (`admin/admin`, service accounts). The security filter chain
is always active.

This means:
- The demo script authenticates with `-u admin:admin` on every curl call
- Smoke tests authenticate with `setBasicAuth(USERNAME, PASSWORD)`
- The test framework includes explicit tests that unauthenticated requests
  return 401

If security were disabled for testing, the demo would work — but the smoke
test that verifies "unauthenticated requests are rejected" would be
meaningless. By keeping security enabled, the tests verify the actual
security behavior that will run in production.

**Why this matters:** "Works in dev, broken in prod" security failures
happen when the test environment does not match production. Testing with
real authentication eliminates an entire class of deployment surprises.

---

## 3. Privacy is enforced structurally, not by convention

The Verification of Payee (VoP) service must never reveal the account
holder's actual name when the match result is NO_MATCH. This prevents
name harvesting — an attacker submitting random names against known IBANs
to discover who holds each account.

The enforcement is structural:

```java
// VoPController.java — actual_name only on CLOSE_MATCH
String actualName = null;
if (bestMatch.result() == VoPResult.CLOSE_MATCH) {
    actualName = entry.holderName();
}
// NO_MATCH: actualName stays null — no code path can leak it
```

The variable is initialized to `null` and only assigned on CLOSE_MATCH.
There is no `else` branch that could accidentally include the name. The
smoke test suite includes a dedicated test that verifies NO_MATCH responses
contain `null` for the name field.

The external responder endpoint (`/check`) applies the same pattern
independently — the privacy rule is enforced in both the internal and
external API paths.

**Why this matters:** Privacy violations in financial systems are regulatory
incidents. Structural enforcement — where the wrong behavior requires
deliberately breaking the code — is more reliable than relying on developers
to remember a convention.

---

## 4. Timing side-channel protection

The VoP external endpoint normalizes response timing to a minimum 10ms floor:

```java
private void applyTimingFloor(long startNanos) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    long remainingMs = 10 - elapsedMs;
    if (remainingMs > 0) {
        Thread.sleep(remainingMs);
    }
}
```

Without this, an attacker could distinguish cache hits (fast) from cache
misses (slow) and from not-found responses (fastest), leaking information
about which IBANs have accounts. With the timing floor, all cached responses
are indistinguishable at ~10ms.

**Why this matters:** Timing side-channels are on the OWASP API Security
Top 10. Most developers do not think about them. Addressing them in a
portfolio project demonstrates awareness of attack vectors beyond the
obvious injection and authentication categories.

---

## 5. Financial integrity through domain types

Money is not represented as `double` or `float` anywhere in the system.
The `MonetaryAmount` value object enforces:

- **BigDecimal with HALF_EVEN rounding** (banker's rounding) — no
  floating-point precision loss
- **Non-negative amounts** — enforced at construction time, not by
  validation annotations
- **Currency match on arithmetic** — adding NOK to EUR throws immediately,
  not after settlement
- **ISO 4217 scale** — NOK amounts have 2 decimal places, enforced by
  the Currency enum

The conservation of money invariant is tested at every layer:
- Unit tests verify double-entry arithmetic
- Use case tests verify reserve + release = original balance
- Smoke tests verify total money before transfer equals total after

PostgreSQL columns use `NUMERIC` (arbitrary precision), not `FLOAT` or
`DOUBLE PRECISION`.

**Why this matters:** Floating-point money bugs are silent — they pass
all tests and only manifest as rounding discrepancies in reconciliation,
sometimes months later. Using domain types that make the wrong
representation impossible is the only reliable prevention.

---

## 6. Input validation at system boundaries

Every REST endpoint uses Jakarta Bean Validation:

```java
record VerifyRequest(
    @NotBlank String beneficiaryIban,
    @NotBlank @Size(max = 140) String statedName,
    String requestId
) {}
```

- `@NotBlank` prevents empty strings and null
- `@Size(max = 140)` prevents oversized input (EPC288-23 name length limit)
- `@Valid` on controller parameters triggers validation before business logic

Internal code trusts its own types — `MonetaryAmount` cannot be negative,
`Currency` cannot be invalid, `PaymentStatus` is an enum. Validation happens
at the boundary; the interior of the system operates on already-validated
domain objects.

**Why this matters:** Defense in depth starts at the API boundary. Validating
input once, correctly, at entry — and then trusting validated types
internally — is cleaner and more secure than scattering null checks and
range validations throughout business logic.

---

## 7. State machine enforcement prevents invalid transitions

The payment processing pipeline uses explicit state machines with static
transition tables:

```java
// Only these transitions are legal — anything else throws
RECEIVED        → AWAITING_RESERVATION
AWAITING_RESERVATION → AWAITING_FRAUD
AWAITING_FRAUD  → SETTLING
SETTLING        → SETTLED
// Terminal states have no outgoing transitions
```

Terminal states (`SETTLED`, `REJECTED`) cannot transition further. The
`isTerminal()` method is checked before any state change. Bidirectional
idempotency ensures that `confirm` after `release` is a no-op, not an
error or a double-credit.

The R2P lifecycle (A3) has 12 states and 14 triggers with the same
pattern — a static transition table where invalid moves throw
`IllegalStateException`, and terminal states are enforced structurally.

**Why this matters:** In payment systems, invalid state transitions cause
real financial harm — double settlements, orphaned reservations, stuck
payments. Making the state machine explicit and the transitions enumerable
means the system's behavior is auditable and testable.

---

## 8. Sanctions screening short-circuits the pipeline

When the sanctions screening layer returns a HIT, the payment is rejected
immediately. The fraud scoring pipeline does not continue — it
short-circuits. This is not an optimization; it is a regulatory requirement.
A sanctions-flagged payment must never proceed, regardless of what other
scoring layers would say.

The implementation uses a Chain of Responsibility pattern where the
sanctions layer runs first and can terminate the chain:

```java
if (sanctionsResult == SanctionsResult.HIT) {
    return FraudDecision.BLOCK;  // short-circuit, no further scoring
}
```

The sanctions index uses a Bloom filter for fast negative lookups, exact
string matching for confirmed hits, and Jaro-Winkler fuzzy matching
(threshold 0.85) for variant spellings. The Bloom filter was specifically
restructured so that it only gates the exact match path — fuzzy matching
always runs, because variant spellings are exactly the case where the
Bloom filter returns false negatives.

**Why this matters:** AML/sanctions compliance is non-negotiable in payment
systems. Getting the screening architecture wrong — allowing payments to
bypass sanctions checks due to timeout, ordering bugs, or filter
misconfiguration — is a regulatory violation with severe consequences.

---

## 9. PSD2 SCA with real exemption logic

The SCA (Strong Customer Authentication) implementation is not a stub.
It includes all four exemption types from the PSD2 RTS:

- **Low-value:** Transactions under EUR 30 (cumulative limit EUR 100)
- **Trusted beneficiary:** Previously approved recipients
- **Recurring:** Fixed-amount recurring payments after initial SCA
- **Transaction Risk Analysis:** Dynamic thresholds per EBA fraud-rate
  bands (EUR 100 at ≤0.13%, EUR 250 at ≤0.06%, EUR 500 at ≤0.01%)

The SCA session enforces:
- 3-attempt lockout (durable — survives restart)
- 5-minute timeout (from session creation, not last attempt)
- Immutable dynamic linking (amount + payee bound at session start,
  cannot be modified)

**Why this matters:** PSD2 SCA is legally required for European payment
services. The exemption engine is where real-world complexity lives —
the four exemption types interact with each other (priority ordering,
cumulative limits, fraud-rate lookups). Implementing this correctly
demonstrates understanding of the regulation, not just the API.

---

## 10. Systematic security review, not ad-hoc checking

The project includes a dedicated security review based on OWASP ASVS
Level 2, covering:

- Authentication and session management
- Access control (deny-all default verified)
- Input validation and output encoding
- Cryptographic practices
- Error handling and logging (no sensitive data in logs)
- API security (OWASP API Security Top 10)

This review is one of 11 review categories applied to the project. Others
include crash resilience, financial integrity, regulatory compliance,
scalability, and certification readiness. The reviews produced 138+
findings, all resolved before the implementation was considered complete.

**Why this matters:** Security in banking is not a checklist at the end —
it is a continuous process integrated into every phase. Having systematic
reviews with traceable findings and resolutions demonstrates the process
maturity that regulated environments require.

---

## 11. Four-layer testing catches security regressions

The test framework includes security-specific tests at every layer:

- **Unit tests:** `DenyAllSecurityTest` verifies the starter's fortress
  principle — unknown endpoints return 403, actuator is public
- **Use case tests:** VoP tests verify NO_MATCH never reveals actual name
- **Master use case tests:** Sanctions HIT always blocks, regardless of
  other scoring layers
- **Smoke tests:** Unauthenticated HTTP requests return 401 against
  running services with real security configuration

The smoke test layer was specifically created because unit tests cannot
verify security configuration against a running Spring Security filter
chain. A test that bypasses HTTP and calls a service method directly does
not test whether the endpoint is actually authenticated.

**Why this matters:** Security regressions are silent — they do not cause
test failures unless there are tests specifically designed to verify
security behavior. Having security assertions at every test layer means
a change that accidentally opens an endpoint will be caught before it
reaches production.

---

## What could be improved

**OAuth2 and mTLS.** The demo uses HTTP Basic auth. Production banking
systems use OAuth2 for user authentication and mTLS for service-to-service
communication. The `DemoSecurityConfig` is correctly isolated to demo/docker
profiles, but adding a production security configuration — even if only
documented, not fully implemented — would show awareness of the full
authentication architecture.

**Rate limiting.** The VoP specification requires rate limiting (100
queries/min per PSP) and anti-enumeration detection (flag >5 queries to
the same IBAN with different names within 1 hour). The architecture
documents describe this, but the implementation does not yet enforce it.
The Bucket4j dependency was identified but not integrated.

**Secrets management.** Database credentials are in `application.yml`
and `docker-compose.yml`. In production, these would come from a secrets
manager (HashiCorp Vault, AWS Secrets Manager, Kubernetes Secrets).
The current setup is appropriate for a demo but should be documented
as a known gap.

**Audit trail.** The `AuditLogger` exists in the security starter, and
Kafka audit events are defined, but comprehensive audit logging of
security-relevant events (authentication attempts, authorization failures,
data access) is not fully wired through all services.

**SAST/DAST integration.** The project has no static analysis (SpotBugs,
SonarQube) or dependency scanning (OWASP Dependency-Check) configured.
Adding these to the Gradle build would demonstrate the "shift left"
security testing approach.

**Penetration testing documentation.** Running OWASP ZAP or Burp Suite
against the running services and documenting the results — even if clean —
would add a verification layer that code review alone cannot provide.

---

## Project at a Glance

| Dimension | Detail |
|---|---|
| Language | Java 21, Spring Boot 3.4 |
| Services | 6 microservices + 3 shared libraries |
| Code | 9,600 lines across 114 files |
| Tests | 278 across 4 layers |
| Security standard | OWASP ASVS Level 2 |
| Regulations implemented | PSD2, EU IPR 2024/886, AML/sanctions |
| Review categories | 11 (security, financial integrity, regulatory, ...) |
| Review findings resolved | 138+ |
| Architecture decisions | 32, each with rationale |
| Demo | Live with real infrastructure (Kafka, PostgreSQL, Docker) |

Target roles: Senior Java Developer, Security-Focused Software Architect,
Banking/Fintech Backend Engineer — particularly at Mastercard Oslo, DNB,
SpareBank 1, Tietoevry, or similar Nordic financial institutions.
