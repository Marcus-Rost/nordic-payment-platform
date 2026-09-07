package com.jrpay.starter.error;

import com.jrpay.domain.exception.DomainException;
import com.jrpay.domain.exception.InfrastructureException;
import com.jrpay.domain.exception.InsufficientBalanceException;
import com.jrpay.domain.exception.AccountFrozenException;
import com.jrpay.domain.exception.SanctionsHitException;
import com.jrpay.domain.exception.DuplicatePaymentException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Global exception handler mapping domain exceptions to RFC 9457 Problem Details.
 * Auto-configured for all services using the Platform Starter.
 *
 * <p>No stack traces or internal details are ever exposed in production.
 */
@RestControllerAdvice
public class PaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientBalanceException ex) {
        ProblemDetail problem = createProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                "insufficient-balance", "Insufficient Balance", ex.getMessage());
        log.warn("Insufficient balance: {}", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ProblemDetail handleAccountFrozen(AccountFrozenException ex) {
        ProblemDetail problem = createProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                "account-frozen", "Account Frozen", ex.getMessage());
        log.warn("Account frozen: {}", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(SanctionsHitException.class)
    public ProblemDetail handleSanctionsHit(SanctionsHitException ex) {
        // Do not log matched entry details at WARN — could be tipping off
        ProblemDetail problem = createProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                "sanctions-hit", "Payment Blocked",
                "Payment blocked due to regulatory screening");
        log.info("Sanctions hit processed");
        return problem;
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    public ProblemDetail handleDuplicate(DuplicatePaymentException ex) {
        ProblemDetail problem = createProblem(HttpStatus.CONFLICT,
                "duplicate-payment", "Duplicate Payment", ex.getMessage());
        log.info("Duplicate payment: {}", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        ProblemDetail problem = createProblem(HttpStatus.UNPROCESSABLE_ENTITY,
                "domain-error", "Business Rule Violation", ex.getMessage());
        log.warn("Domain exception: {}", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        // @Valid request-body validation failures. Surface field-level
        // violations so API consumers can correct the request without
        // guessing which field failed; safe — these come from the request
        // the caller themselves sent.
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        ProblemDetail problem = createProblem(HttpStatus.BAD_REQUEST,
                "validation", "Validation failed", "One or more fields failed validation");
        problem.setProperty("fields", fields);
        log.info("Validation failed: {}", fields);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        // @Validated method-parameter violations (e.g. @RequestParam @NotBlank).
        Map<String, String> fields = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage() == null ? "invalid" : v.getMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        ProblemDetail problem = createProblem(HttpStatus.BAD_REQUEST,
                "validation", "Validation failed", "One or more parameters failed validation");
        problem.setProperty("fields", fields);
        log.info("Constraint violation: {}", fields);
        return problem;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        // Required @RequestParam not present.
        ProblemDetail problem = createProblem(HttpStatus.BAD_REQUEST,
                "missing-parameter", "Missing required parameter",
                "Required parameter '" + ex.getParameterName() + "' is not present");
        problem.setProperty("parameter", ex.getParameterName());
        log.info("Missing parameter: {}", ex.getParameterName());
        return problem;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        // Anti-enumeration: identical 401 + identical message for "wrong
        // password" and "unknown user" cases. Callers cannot distinguish
        // a typo'd password from an unregistered account.
        ProblemDetail problem = createProblem(HttpStatus.UNAUTHORIZED,
                "bad-credentials", "Authentication failed",
                "The supplied credentials are not valid");
        log.info("Bad credentials");
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        // Honour an explicit HTTP status set by the controller (used for
        // 403 audience-mismatch / account-suspended in DevTokenController,
        // and any other places that want a specific error code without
        // declaring a new exception type).
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = createProblem(status,
                status.is4xxClientError() ? "client-error" : "server-error",
                status.getReasonPhrase(),
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason());
        log.info("ResponseStatusException: {} {}", status.value(), ex.getReason());
        return problem;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNotFound(NoResourceFoundException ex) {
        // Spring's "no controller mapped to this path" maps to 404, not the
        // generic 500. Without this, every uncovered customer-API path
        // surfaces as `urn:problem:internal-error` and hides routing bugs
        // behind generic noise.
        ProblemDetail problem = createProblem(HttpStatus.NOT_FOUND,
                "not-found", "Resource not found",
                "The requested resource does not exist");
        problem.setProperty("path", ex.getResourcePath());
        log.info("Resource not found: {}", ex.getResourcePath());
        return problem;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // Wrong HTTP verb on an existing path → 405, not 500. Echoes the
        // supported methods so the caller can correct without trial-and-error.
        ProblemDetail problem = createProblem(HttpStatus.METHOD_NOT_ALLOWED,
                "method-not-allowed", "Method Not Allowed",
                "HTTP method " + ex.getMethod() + " is not supported on this resource");
        if (ex.getSupportedMethods() != null) {
            problem.setProperty("supported", java.util.List.of(ex.getSupportedMethods()));
        }
        log.info("Method not supported: {} (supported: {})",
                ex.getMethod(), ex.getSupportedMethods());
        return problem;
    }

    @ExceptionHandler(InfrastructureException.class)
    public ProblemDetail handleInfrastructure(InfrastructureException ex) {
        ProblemDetail problem = createProblem(HttpStatus.SERVICE_UNAVAILABLE,
                "service-unavailable", "Service Unavailable",
                "An internal service is temporarily unavailable. Please retry.");
        log.error("Infrastructure exception: {}", ex.getMessage(), ex);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Last resort — no internal details exposed
        ProblemDetail problem = createProblem(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error", "Internal Server Error",
                "An unexpected error occurred. Please contact support with the correlation ID.");
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        return problem;
    }

    private ProblemDetail createProblem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://api.jrpay.com/errors/" + type));
        problem.setTitle(title);
        problem.setProperty("correlationId", UUID.randomUUID().toString());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
