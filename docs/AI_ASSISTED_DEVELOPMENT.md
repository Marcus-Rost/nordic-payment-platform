# Why This Project Demonstrates AI-Assisted Development Expertise

This project was built entirely through human-AI collaboration — one domain
expert directing an AI assistant across the full software engineering lifecycle.
The result is not a toy demo. It is a production-grade payment processing
system with 6 microservices, 9,600 lines of Java, 278 tests, and 67
architecture documents.

Here is what that demonstrates, and why it matters.

---

## 1. The output is a system, not a collection of files

Most AI-assisted projects produce isolated scripts or single-file utilities.
This project produces a coherent distributed system where services share domain
types, communicate over Kafka, enforce consistent security policies through a
shared starter, and are tested at four distinct layers.

Maintaining coherence across 114 Java files and 10 subprojects requires the
human to hold the architectural vision while the AI generates the
implementation. The AI does not do this on its own — left undirected, it
produces locally correct code that contradicts decisions made elsewhere. The
human's role is to prevent that drift.

**What this shows:** The ability to use AI as a force multiplier for large-scale
work, not just a code completion tool for individual functions.

---

## 2. The review loop is the methodology

The project was built in phases: requirements, architecture, implementation,
testing. At each phase boundary, systematic reviews were run — 11 review
categories, 138+ findings identified and resolved before moving forward.

This is the core discipline of AI-assisted development: **generate, review,
fix, verify.** The AI produces drafts quickly. The human evaluates whether those
drafts are correct, consistent, and complete. Skipping the review step is how
AI-assisted projects produce confident-looking code with subtle bugs.

The review categories were not invented after the fact. They were designed
upfront from public standards:
- OWASP ASVS Level 2 for security
- EBA RTS for PSD2 compliance
- EU IPR 2024/886 for instant payments
- ISO 20022 for message formats
- DORA for operational resilience

**What this shows:** A repeatable methodology for quality-controlled AI output,
not ad-hoc prompting.

---

## 3. The AI made real bugs, and the human caught them

AI-generated code is not correct by default. This project documents specific
cases where the AI introduced defects that required human detection and
correction:

- **Unicode normalization failure.** The AI generated NFD-based diacritics
  handling, but NFD does not decompose the Norwegian letter ø (it is a distinct
  Unicode codepoint, not a composed character). This was caught by testing with
  Norwegian names and fixed by adding pre-NFD replacement mappings.

- **Jaro-Winkler asymmetry.** The AI generated a best-permutation matching
  algorithm that only permuted the shorter token list. Property-based testing
  (500 random name pairs) revealed that `match(a, b) != match(b, a)`. Fixed
  by permuting both directions and taking the maximum.

- **Bloom filter blocking fuzzy matching.** The AI placed the fuzzy match
  behind the Bloom filter, but the Bloom filter returns false for variant
  spellings — which is exactly when fuzzy matching is needed. The entire fuzzy
  matching tier was unreachable. Caught by sanctions screening tests.

- **Conservation of money violation.** The scoring pipeline captured the
  sanctions list version after the scoring loop, but sanctions hits short-
  circuit before the loop completes — so the version was never captured.
  This was a data integrity issue, not a crash.

- **Health check port confusion.** The AI generated smoke tests that checked
  `/actuator/health` on the app port. With a separate management port
  configured, the app port returns 500 (security error handler intercepts
  the request). Tests silently skipped, reporting BUILD SUCCESSFUL with zero
  tests actually executed.

None of these bugs were obvious. Each required domain knowledge, systematic
testing, or understanding of framework behavior to identify. An operator who
blindly trusts AI output would have shipped all five.

**What this shows:** The critical skill is not prompting — it is knowing when
the AI is wrong, and having the testing discipline to surface the errors
systematically.

---

## 4. The test framework was designed to catch AI blind spots

The four-layer test framework exists because AI-generated unit tests have a
structural limitation: they test what the AI thinks the code should do, using
the same assumptions the AI used to generate the code. If the assumption is
wrong, both the code and the test agree — and the bug passes.

The smoke test layer was added after a live demo revealed three integration
failures that all 223 unit tests missed:
- VoP service was not calling A1 on cache miss (the code path was never
  exercised because the unit test pre-populated the cache)
- Security configuration blocked API calls (the unit test used a test profile
  that bypassed security)
- A transfer endpoint did not exist (the unit test called the command handler
  directly, not through HTTP)

The human recognized that these failures shared a common cause — in-process
tests cannot verify cross-service HTTP integration, real authentication, or
real database behavior — and designed a test layer specifically to cover that
gap.

**What this shows:** Understanding the limits of AI-generated tests and
designing compensating verification strategies.

---

## 5. Domain expertise cannot be delegated to the AI

The AI does not know Norwegian payment regulations. It does not know that the
EU Instant Payment Regulation 2024/886 makes VoP mandatory from October 2025.
It does not know that PSD2 SCA exemptions have specific EBA fraud-rate
thresholds per transaction band. It does not know that ø and æ require special
handling in Nordic name matching.

All of this domain knowledge came from the human. The AI's role was to
implement the decisions the human made based on that knowledge — quickly,
consistently, and at a scale that would take a single developer weeks to
produce manually.

This is the correct division of labor: the human provides judgment, domain
knowledge, and quality control. The AI provides speed, consistency, and
tireless execution of well-specified tasks.

**What this shows:** AI-assisted development is not "AI does the work." It is
"the human does the thinking, and the AI makes the thinking real at 10x speed."

---

## 6. The scale is the proof

The project contains:

| Artifact | Count |
|---|---|
| Java source files | 114 |
| Lines of Java code | 9,600 |
| Tests across 4 layers | 278 |
| Architecture documents | 27 |
| Review documents | 40 |
| Review findings resolved | 138+ |
| Architecture decisions | 32 |
| Use cases | 10 |
| Microservices | 6 |
| Shared libraries | 3 |

This was produced by one person with AI assistance. The same scope without AI
would require a team or months of solo work. The quality — tested, reviewed,
documented, demonstrable — is what makes the scale meaningful. Volume without
quality is just generated text.

**What this shows:** AI-assisted development, done with discipline, changes
what one person can accomplish.

---

## What could be improved

An honest assessment of where the project falls short and what would make
it stronger as a demonstration of AI-assisted development.

**Process documentation is missing.** The project shows the output but not the
journey. There is no record of how many iterations each component took, which
prompts worked well, which led to dead ends, or how much human intervention
was required per phase. A `PROCESS.md` documenting the collaboration workflow
— prompt strategies, review cadence, error rates, rework patterns — would
make the AI expertise claim much more concrete. Right now, a reviewer has to
take it on faith that AI was involved at all.

**No timeline or effort metrics.** "One person built this with AI" is
powerful, but "one person built this in 12 sessions over 3 weeks" is
quantifiable. Without a timeline, there is no way to measure the
productivity multiplier. Tracking session count, token usage, and wall-clock
time per phase would provide the data points that AI-expertise recruiters
want to see.

**Single AI tool, single model.** The entire project used one assistant.
An AI-expertise role often wants someone who has evaluated alternatives —
different models, different tools, different integration patterns — and can
articulate tradeoffs. Adding a section comparing the experience with
different approaches (code generation vs. architecture review vs. test
generation) would show deeper tool awareness.

**No failed experiment documentation.** The project documents bugs that were
caught and fixed, but not approaches that were tried and abandoned. Dead
ends are valuable — they show judgment about when to change direction.
Documenting cases where the AI's architectural suggestion was rejected,
and why, would demonstrate the critical evaluation skill more clearly.

**The demo could be automated end-to-end.** Currently, starting
infrastructure, services, running the demo, and running smoke tests are
separate manual steps. A single `make demo` or CI pipeline that does
everything from a clean checkout would demonstrate operational maturity
and make the project easier for reviewers to evaluate. The BUILD_GUIDE.md
documents the steps, but automation proves they work.

**Load testing and observability are absent.** The project proves
correctness but not operational readiness. Adding a simple Grafana
dashboard, structured logging with correlation IDs, and a basic load
test (even just wrk or k6 against the running services) would close the
gap between "it works" and "it works under realistic conditions."

**The AI's contribution could be made visible in the commit history.**
If the project were rebuilt with a disciplined commit strategy —
AI-generated commits clearly labeled, human review commits separate —
the git history itself would tell the collaboration story. This would
make the AI-assisted development claim verifiable rather than stated.
