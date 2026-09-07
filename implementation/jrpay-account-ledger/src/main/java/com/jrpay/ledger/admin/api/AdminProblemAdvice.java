package com.jrpay.ledger.admin.api;

import com.jrpay.ledger.admin.AdminProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * RFC 9457 problem mapping for the admin controllers.
 */
@ControllerAdvice(basePackages = "com.jrpay.ledger.admin.api")
public class AdminProblemAdvice {

    @ExceptionHandler(AdminProblemException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminProblemException e, HttpServletRequest req) {
        return problemResponse(e.getStatus(), e.getType(), e.getTitle(), e.getDetail(),
                e.getExtensions(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
                                                                HttpServletRequest req) {
        String detail = e.getBindingResult().getAllErrors().isEmpty()
                ? "Request body failed validation"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return problemResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation",
                "Validation failed",
                detail, null, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e,
                                                                HttpServletRequest req) {
        return problemResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:problem:validation",
                "Validation failed",
                e.getMessage(), null, req);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthzDenied(AuthorizationDeniedException e,
                                                                 HttpServletRequest req) {
        return problemResponse(HttpStatus.FORBIDDEN,
                "urn:problem:forbidden",
                "Forbidden",
                e.getMessage(), null, req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e,
                                                                  HttpServletRequest req) {
        return problemResponse(HttpStatus.FORBIDDEN,
                "urn:problem:forbidden",
                "Forbidden",
                e.getMessage(), null, req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthn(AuthenticationException e,
                                                           HttpServletRequest req) {
        return problemResponse(HttpStatus.UNAUTHORIZED,
                "urn:problem:unauthenticated",
                "Unauthenticated",
                e.getMessage(), null, req);
    }

    private static ResponseEntity<Map<String, Object>> problemResponse(HttpStatus status, String type,
                                                                       String title, String detail,
                                                                       Map<String, Object> extensions,
                                                                       HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        if (detail != null) body.put("detail", detail);
        if (req != null && req.getRequestURI() != null) {
            body.put("instance", URI.create(req.getRequestURI()).toString());
        } else {
            body.put("instance", "urn:correlation:" + UUID.randomUUID());
        }
        if (extensions != null) body.putAll(extensions);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
