package com.jrpay.fraud.casework;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class CaseworkProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final String detail;
    private final Map<String, Object> extensions;

    public CaseworkProblemException(HttpStatus status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public CaseworkProblemException(HttpStatus status, String type, String title, String detail,
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

    public static CaseworkProblemException notFound(String detail) {
        return new CaseworkProblemException(HttpStatus.NOT_FOUND,
                "urn:problem:not-found", "Resource not found", detail);
    }

    public static CaseworkProblemException stepUpRequired(String detail) {
        return new CaseworkProblemException(HttpStatus.UNAUTHORIZED,
                "urn:problem:step-up-required", "Step-up SCA required", detail);
    }

    public static CaseworkProblemException dynamicLinkingMismatch(String detail) {
        return new CaseworkProblemException(HttpStatus.FORBIDDEN,
                "urn:problem:dynamic-linking-mismatch", "Dynamic-linking mismatch", detail);
    }

    public static CaseworkProblemException caseAlreadyDecided(String caseId) {
        return new CaseworkProblemException(HttpStatus.CONFLICT,
                "urn:problem:case-already-decided", "Case already decided",
                "Case " + caseId + " has already been decided");
    }

    public static CaseworkProblemException hitAlreadyDecided(String hitId) {
        return new CaseworkProblemException(HttpStatus.CONFLICT,
                "urn:problem:hit-already-decided", "Hit already decided",
                "Sanctions hit " + hitId + " has already been decided");
    }

    public static CaseworkProblemException concurrentLimit(String detail) {
        return new CaseworkProblemException(HttpStatus.TOO_MANY_REQUESTS,
                "urn:problem:concurrent-limit",
                "Concurrent operation limit reached", detail);
    }

    public static CaseworkProblemException validation(String detail) {
        return new CaseworkProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation", "Validation failed", detail);
    }

    public static CaseworkProblemException invalidInput(String detail) {
        return new CaseworkProblemException(HttpStatus.BAD_REQUEST,
                "urn:problem:invalid-input", "Invalid input", detail);
    }

    public static CaseworkProblemException forbidden(String detail) {
        return new CaseworkProblemException(HttpStatus.FORBIDDEN,
                "urn:problem:forbidden", "Forbidden", detail);
    }

    public static CaseworkProblemException identityUnavailable(String detail) {
        return new CaseworkProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "urn:problem:identity-unavailable",
                "Identity service unavailable", detail);
    }

    public static CaseworkProblemException listRefreshInProgress(String detail) {
        return new CaseworkProblemException(HttpStatus.TOO_MANY_REQUESTS,
                "urn:problem:list-refresh-in-progress",
                "List refresh already running", detail);
    }
}
