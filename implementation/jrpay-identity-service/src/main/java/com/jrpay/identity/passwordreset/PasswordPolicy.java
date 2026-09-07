package com.jrpay.identity.passwordreset;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Password policy enforced on every password mutation (UC-G44 §6 step 10).
 *
 * <p>Two checks:
 * <ul>
 *   <li>Length + character-class composition per
 *       {@code application.yml#jrpay.identity.password-policy.*}.
 *   <li>Breached-list — a tiny embedded set of common passwords stands in for
 *       the production HIBP-k-anonymity check. The set is non-exhaustive but
 *       catches the obvious "Password1!" style cases the test fixtures use.
 * </ul>
 */
@Component
public class PasswordPolicy {

    private static final Set<String> KNOWN_BREACHED = Set.of(
            "password",
            "password1",
            "password1!",
            "letmein",
            "qwerty",
            "qwerty123",
            "111111",
            "12345678",
            "iloveyou",
            "welcome1",
            "admin1234",
            "demo-password-1!"
    );

    private final int minLength;
    private final int maxLength;
    private final boolean requireMixedCase;
    private final boolean requireDigit;
    private final boolean requireSymbol;

    public PasswordPolicy(@Value("${jrpay.identity.password-policy.min-length:10}") int minLength,
                          @Value("${jrpay.identity.password-policy.max-length:128}") int maxLength,
                          @Value("${jrpay.identity.password-policy.require-mixed-case:true}") boolean requireMixedCase,
                          @Value("${jrpay.identity.password-policy.require-digit:true}") boolean requireDigit,
                          @Value("${jrpay.identity.password-policy.require-symbol:false}") boolean requireSymbol) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.requireMixedCase = requireMixedCase;
        this.requireDigit = requireDigit;
        this.requireSymbol = requireSymbol;
    }

    public Result evaluate(String candidate) {
        if (candidate == null || candidate.length() < minLength || candidate.length() > maxLength) {
            return Result.LENGTH_FAIL;
        }
        if (KNOWN_BREACHED.contains(candidate.toLowerCase())) {
            return Result.BREACHED;
        }
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSymbol = true;
        }
        if (requireMixedCase && !(hasUpper && hasLower)) return Result.NEEDS_MIXED_CASE;
        if (requireDigit && !hasDigit) return Result.NEEDS_DIGIT;
        if (requireSymbol && !hasSymbol) return Result.NEEDS_SYMBOL;
        return Result.OK;
    }

    public enum Result {
        OK,
        LENGTH_FAIL,
        NEEDS_MIXED_CASE,
        NEEDS_DIGIT,
        NEEDS_SYMBOL,
        BREACHED;

        public boolean ok() { return this == OK; }
    }
}
