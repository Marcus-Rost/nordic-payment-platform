package com.jrpay.psd2.customer;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class CustomerProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final String detail;
    private final Map<String, Object> extensions;

    public CustomerProblemException(HttpStatus status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public CustomerProblemException(HttpStatus status, String type, String title, String detail,
                                    Map<String, Object> extensions) {
        super(title + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.type = type;
        this.title = title;
        this.detail = detail;
        this.extensions = extensions;
    }

    public HttpStatus getStatus() { return status; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public Map<String, Object> getExtensions() { return extensions; }

    public static CustomerProblemException notFound(String detail) {
        return new CustomerProblemException(HttpStatus.NOT_FOUND,
                "urn:problem:not-found", "Resource not found", detail);
    }

    public static CustomerProblemException stepUpRequired(String detail) {
        return new CustomerProblemException(HttpStatus.UNAUTHORIZED,
                "urn:problem:step-up-required", "Step-up SCA required", detail);
    }

    public static CustomerProblemException dynamicLinkingMismatch(String detail) {
        return new CustomerProblemException(HttpStatus.FORBIDDEN,
                "urn:problem:dynamic-linking-mismatch",
                "Dynamic-linking mismatch", detail);
    }

    public static CustomerProblemException duplicateSuspected(String detail, Map<String, Object> original) {
        Map<String, Object> ext = new LinkedHashMap<>();
        if (original != null) ext.put("suspectedOriginal", original);
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:duplicate-suspected", "Duplicate suspected", detail, ext);
    }

    public static CustomerProblemException idempotencyKeyConflict(String detail) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:idempotency-key-conflict",
                "Idempotency-Key reuse with different body", detail);
    }

    public static CustomerProblemException insufficientFunds(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:insufficient-funds", "Insufficient funds", detail);
    }

    public static CustomerProblemException accountFrozen(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:account-frozen", "Account frozen", detail);
    }

    public static CustomerProblemException accountClosed(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:account-closed", "Account closed", detail);
    }

    public static CustomerProblemException unsupportedCurrency(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:unsupported-currency", "Unsupported currency", detail);
    }

    public static CustomerProblemException rateLimited(String detail) {
        return new CustomerProblemException(HttpStatus.TOO_MANY_REQUESTS,
                "urn:problem:rate-limited", "Rate limit exceeded", detail);
    }

    public static CustomerProblemException invalidInput(String detail) {
        return new CustomerProblemException(HttpStatus.BAD_REQUEST,
                "urn:problem:invalid-input", "Invalid input", detail);
    }

    public static CustomerProblemException validation(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation", "Validation failed", detail);
    }

    public static CustomerProblemException invalidIban(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:invalid-iban", "Invalid IBAN", detail);
    }

    public static CustomerProblemException identityUnavailable(String detail) {
        return new CustomerProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "urn:problem:identity-unavailable", "Identity service unavailable", detail);
    }

    public static CustomerProblemException paymentNotCancellable(String detail) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:payment-not-cancellable",
                "Payment cannot be cancelled at this stage", detail);
    }
}
