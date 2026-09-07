# JRPay — Nordic Payment Processing Infrastructure: Feature Summary

## Overview

JRPay is a payment processing platform comprising six integrated microservices that handle the complete lifecycle of electronic payments — from invoice submission through clearing, fraud screening, and settlement. The system is designed for the Norwegian and European payment market, implementing EU regulatory requirements (Instant Payment Regulation, PSD2, AML, GDPR, DORA) and Norwegian-specific standards (NICS, BankID, KID numbers).

The project demonstrates production-grade security, financial integrity, crash resilience, and regulatory compliance — not as afterthoughts, but as foundational architectural properties.

## Core Payment Services

**A1 — Event-Sourced Account Ledger.** The single source of truth for all account balances. Uses event sourcing with Kafka as the event store and PostgreSQL for projections. Compound events (debit + credit in a single message) make unbalanced entries structurally impossible. Supports balance reservations with 30-second auto-release safety net, account freeze/unfreeze for regulatory holds, and multi-currency accounts (NOK, EUR, SEK, DKK, USD) with ISO 4217 compliance. Projections are fully rebuildable from the event stream at any time.

**A2 — Payment Clearing and Settlement Engine.** The orchestrator for the instant payment pipeline. Implements a strict state machine (RECEIVED → AWAITING_RESERVATION → AWAITING_FRAUD → SETTLING → SETTLED) where no step can be skipped. Sanctions screening is structurally mandatory — there is no code path, feature flag, or configuration that bypasses it. Supports both instant payments (5-second EPC 2025 SLA with 7-second hard limit) and batch clearing (multilateral netting with Spring Batch). Semantic duplicate detection warns on potential double payments without blocking legitimate recurring transfers.

**A3 — Bill Payment and Request-to-Pay Service.** Handles the merchant-consumer payment lifecycle with a 12-state state machine covering accept, decline, defer, and amend flows. Supports recurring mandates (AvtaleGiro-style) with auto-acceptance when amounts are within the mandate limit. Processes payment status updates from A2, including SETTLED, REJECTED, HELD, RETURN_INITIATED, and RETURNED for late sanctions reversals. All status transitions are enforced by a static transition table — invalid transitions throw exceptions.

**A4 — PSD2 Open Banking API Gateway.** Implements the OAuth2 consent flow with Strong Customer Authentication. The SCA exemption engine evaluates all four PSD2 RTS exemption types: low-value (EUR 30 threshold with cumulative counters), trusted beneficiary, recurring payments, and Transaction Risk Analysis with EBA fraud-rate thresholds. SCA sessions use dynamic linking where payment amount and creditor are immutable after creation (prevents amount-switching attacks). Lockout after 3 failed attempts is durable — persisted in the database, survives service restarts. Consent validity is checked on every request from the database, with zero revocation window.

**A5 — Real-Time Fraud Detection and Sanctions Engine.** Implements a chain-of-responsibility scoring pipeline: sanctions screening (Bloom filter + Jaro-Winkler fuzzy matching) → velocity rules (high frequency, large amounts) → VoP risk adjustment. Sanctions hits short-circuit the pipeline immediately — no further scoring runs. VoP override signals integrate into the fraud score: NO_MATCH + payer override adds +0.3 risk, NOT_FOUND + override adds +0.4. The sanctions index supports atomic list replacement via AtomicReference for zero-downtime updates.

**A6 — Verification of Payee Service.** Pre-payment name-IBAN verification implementing both requester and responder roles per the EU VoP Scheme (EPC218-23). The structural name matching engine normalizes names (EPC288-23 diacritics rules: ø→o, å→a, æ→ae), decomposes legal entity suffixes (AS→Aksjeselskap), tokenizes compound names (van der Berg), and computes best-permutation Jaro-Winkler scores. Actual names are NEVER revealed on NO_MATCH — this is structurally enforced, not configurable. Response timing is normalized to a 10ms floor to prevent timing side-channel attacks.

## Security Properties

The Platform Starter auto-configures a security baseline for all services: deny-all authentication (every endpoint requires credentials), OWASP-recommended security headers (HSTS, CSP, X-Frame-Options, X-Content-Type-Options, no-cache), RFC 9457 Problem Details for all error responses (no stack traces or internal details exposed), and structured audit logging. Services that override the security chain for custom authentication (OAuth2 in A4, API keys in A3) receive a warning log ensuring visibility.

Financial calculations use BigDecimal exclusively — no float or double for monetary values anywhere. The MonetaryAmount domain type enforces non-negative amounts, prevents cross-currency arithmetic, and uses HALF_EVEN (Banker's Rounding). All monetary columns in PostgreSQL use NUMERIC. Amounts in Kafka messages are serialized as strings, not doubles.

## Regulatory Compliance

The system addresses seven regulatory frameworks: EU Instant Payment Regulation (2024/886) including VoP (Article 5c) and sanctions screening (Article 5d with dual screening model), PSD2 with full SCA exemption engine, AML 5th Directive (5-year record retention, sanctions screening), GDPR (crypto-shredding for right to erasure, data minimization), DORA (incident classification, resilience testing), and Norwegian legislation (finansavtaleloven, hvitvaskingsloven, betalingssystemloven). The system applies EU IPR standards uniformly to all currencies, including NOK, as a deliberate architectural choice for simplicity and future-proofing.

## Test Coverage

223 automated tests across 22 test classes, organized in four layers: unit tests (core logic), subproject use-case end-to-end tests (derived from each service's UC-01), master use-case system tests (derived from master UC-01, UC-03, UC-05, UC-08, UC-09), and property-based tests (jqwik — algorithmic invariants like matching symmetry and normalization idempotency). Failure paths are tested alongside happy paths: insufficient balance, account frozen, sanctions hit, fraud block, 7-second timeout, semantic duplicates, consent revocation, SCA lockout.
