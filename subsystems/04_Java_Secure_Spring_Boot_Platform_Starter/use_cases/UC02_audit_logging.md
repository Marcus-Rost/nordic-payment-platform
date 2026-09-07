# UC-02: Structured Audit Logging

*Component: AuditLoggingAutoConfiguration, @Audited annotation, AuditLogger API*

---

## Scenario 2A: HTTP Request Audit via @Audited Annotation

### Preconditions
- Starter is on classpath
- Controller method is annotated with `@Audited`

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Developer | Annotates controller: `@Audited @PostMapping("/payments") public Payment initiate(...)` | AOP aspect registered by auto-configuration |
| 2 | Client | Sends `POST /api/v1/payments` with Bearer token and payment body | Request intercepted by AuditAspect (before + after) |
| 3 | AuditAspect | Extracts context | Captures: timestamp, principal (from JWT `sub` claim), HTTP method, path, source IP, correlation ID (`X-Correlation-Id` header or generates UUID) |
| 4 | — | Method executes | Normal business logic runs |
| 5 | AuditAspect | Captures response | Records: response status (201), latency (42ms) |
| 6 | — | Writes audit log entry | Structured JSON to SLF4J logger named `audit`: |

```json
{
  "timestamp": "2026-04-11T14:23:45.123Z",
  "level": "INFO",
  "logger": "audit",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "principal": "tpp-client-42",
  "action": "POST /api/v1/payments",
  "sourceIp": "10.0.1.42",
  "responseStatus": 201,
  "latencyMs": 42,
  "service": "clearing-engine",
  "environment": "production"
}
```

---

## Scenario 2B: Sensitive Field Redaction

### Preconditions
- Request body contains `{"accountNumber": "NO93860111117947", "amount": 5000, "password": "secret123"}`

### Main Success Scenario

| Step | Action | Result |
|------|--------|--------|
| 1 | AuditAspect intercepts request with `log-request-body: true` (opted in) | Reads request body |
| 2 | Redaction engine applies patterns from `payment.security.audit.redact-patterns` | `accountNumber` → `"***REDACTED***"`, `password` → `"***REDACTED***"` |
| 3 | Audit log includes sanitized body | `"requestBody": {"accountNumber": "***REDACTED***", "amount": 5000, "password": "***REDACTED***"}` |

Note: `log-request-body` is **disabled by default** due to PII risk. Must be explicitly enabled.

---

## Scenario 2C: Programmatic Domain Audit Event

### Preconditions
- Business logic needs to log a domain-specific event (not just HTTP request)

### Main Success Scenario

| Step | Actor | Action |
|------|-------|--------|
| 1 | Developer | Injects `AuditLogger`: `@Autowired AuditLogger auditLogger;` |
| 2 | Developer | Calls: `auditLogger.log("PAYMENT_SETTLED", Map.of("paymentId", id, "amount", amount, "currency", "NOK"))` |
| 3 | — | Produces structured JSON: `{"event": "PAYMENT_SETTLED", "paymentId": "...", "amount": 5000, "currency": "NOK", "timestamp": "...", "correlationId": "...", "principal": "..."}` |

---

## Scenario 2D: Correlation ID Propagation

| Step | Action | Result |
|------|--------|--------|
| 1 | Client sends request with `X-Correlation-Id: abc-123` | Starter extracts and stores in MDC (Mapped Diagnostic Context) |
| 2 | Service A calls Service B (REST) | RestTemplate/WebClient interceptor adds `X-Correlation-Id: abc-123` to outbound request |
| 3 | Service B receives request | Starter extracts same correlation ID |
| 4 | Both services' audit logs contain `"correlationId": "abc-123"` | End-to-end traceability across all services |
| 5 | If no incoming correlation ID | Starter generates UUID v4 and uses that for the entire request chain |

---

## Security Considerations
- Audit logs are written to a dedicated logger (`audit`) with its own appender configuration — separate from application logs
- Audit log entries must never contain: plaintext passwords, bearer tokens, API keys, full IBANs (redact all but last 4 digits)
- Kafka audit publisher (if enabled) uses fire-and-forget — audit logging must never fail the request
- Correlation IDs are UUID v4 (cryptographically random) — never sequential (prevents enumeration)
