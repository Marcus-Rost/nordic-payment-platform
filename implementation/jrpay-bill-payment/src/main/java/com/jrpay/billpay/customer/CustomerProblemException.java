package com.jrpay.billpay.customer;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** RFC 9457 problem container for the customer surface. */
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

    public static CustomerProblemException r2pAlreadyActed(String requestId) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:r2p-already-acted",
                "R2P already acted",
                "R2P " + requestId + " has already been accepted, declined, or deferred");
    }

    public static CustomerProblemException r2pCancelled(String requestId) {
        return new CustomerProblemException(HttpStatus.GONE,
                "urn:problem:r2p-cancelled",
                "R2P cancelled",
                "Merchant cancelled R2P " + requestId);
    }

    public static CustomerProblemException r2pExpired(String requestId) {
        return new CustomerProblemException(HttpStatus.GONE,
                "urn:problem:r2p-expired",
                "R2P expired",
                "R2P " + requestId + " is past its due date");
    }

    public static CustomerProblemException tooLateToCancel(String requestId) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:too-late-to-cancel",
                "Too late to cancel",
                "Schedule for R2P " + requestId + " has already started executing");
    }

    public static CustomerProblemException amendmentNotAllowed(String requestId) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:amendment-not-allowed",
                "Amendment not allowed",
                "Merchant disallows amendments on R2P " + requestId);
    }

    public static CustomerProblemException invalidInput(String detail) {
        return new CustomerProblemException(HttpStatus.BAD_REQUEST,
                "urn:problem:invalid-input",
                "Invalid input", detail);
    }

    public static CustomerProblemException validation(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation",
                "Validation failed", detail);
    }

    public static CustomerProblemException duplicateIban(String detail, String existingId) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("details", Map.of("existingBeneficiaryId", existingId));
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:duplicate-iban",
                "Duplicate IBAN", detail, ext);
    }

    public static CustomerProblemException invalidIban(String detail) {
        return new CustomerProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:invalid-iban",
                "Invalid IBAN", detail);
    }

    public static CustomerProblemException merchantNotInRegistry(String merchantId) {
        return new CustomerProblemException(HttpStatus.NOT_FOUND,
                "urn:problem:merchant-not-in-registry",
                "Merchant not in registry",
                "AvtaleGiro merchant " + merchantId + " is not in the registry");
    }

    public static CustomerProblemException pendingExecution(String detail) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:pending-execution",
                "Pending execution", detail);
    }

    public static CustomerProblemException duplicateConsent(String detail) {
        return new CustomerProblemException(HttpStatus.CONFLICT,
                "urn:problem:duplicate-consent",
                "Duplicate consent", detail);
    }

    public static CustomerProblemException identityUnavailable(String detail) {
        return new CustomerProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "urn:problem:identity-unavailable",
                "Identity service unavailable", detail);
    }
}
