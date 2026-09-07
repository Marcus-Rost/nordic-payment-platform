package com.jrpay.ledger.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Carries an RFC 9457 problem response from the service layer up to the
 * controller's {@link org.springframework.web.bind.annotation.ControllerAdvice}.
 */
public class AdminProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final String detail;
    private final Map<String, Object> extensions;

    public AdminProblemException(HttpStatus status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public AdminProblemException(HttpStatus status, String type, String title, String detail,
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

    public static AdminProblemException stepUpRequired(String detail) {
        return new AdminProblemException(HttpStatus.UNAUTHORIZED,
                "urn:problem:step-up-required",
                "Step-up SCA required",
                detail);
    }

    public static AdminProblemException dynamicLinkingMismatch(String detail) {
        return new AdminProblemException(HttpStatus.FORBIDDEN,
                "urn:problem:dynamic-linking-mismatch",
                "Dynamic-linking mismatch",
                detail);
    }

    public static AdminProblemException notFound(String detail) {
        return new AdminProblemException(HttpStatus.NOT_FOUND,
                "urn:problem:not-found",
                "Resource not found",
                detail);
    }

    public static AdminProblemException accountFrozen(String accountId) {
        return new AdminProblemException(HttpStatus.CONFLICT,
                "urn:problem:account-frozen",
                "Account is frozen",
                "Account " + accountId + " is already frozen");
    }

    public static AdminProblemException accountClosed(String accountId) {
        return new AdminProblemException(HttpStatus.CONFLICT,
                "urn:problem:account-closed",
                "Account is closed",
                "Account " + accountId + " is closed");
    }

    public static AdminProblemException balanceNonzero(String accountId) {
        return new AdminProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:account-balance-nonzero",
                "Account balance is non-zero",
                "Account " + accountId + " has non-zero balance — transfer out before closing");
    }

    public static AdminProblemException pendingExecution(String accountId) {
        return new AdminProblemException(HttpStatus.CONFLICT,
                "urn:problem:pending-execution",
                "Pending execution",
                "Account " + accountId + " has in-flight reservations — wait for completion");
    }

    public static AdminProblemException validation(String detail) {
        return new AdminProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation",
                "Validation failed",
                detail);
    }

    public static AdminProblemException forbidden(String detail) {
        return new AdminProblemException(HttpStatus.FORBIDDEN,
                "urn:problem:forbidden",
                "Forbidden",
                detail);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("title", title);
        map.put("status", status.value());
        if (detail != null) map.put("detail", detail);
        if (extensions != null) map.putAll(extensions);
        return map;
    }
}
