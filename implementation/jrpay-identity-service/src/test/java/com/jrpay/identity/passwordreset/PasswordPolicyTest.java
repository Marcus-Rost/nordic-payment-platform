package com.jrpay.identity.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(10, 128, true, true, false);

    @Test
    void acceptsCompliantPassword() {
        assertThat(policy.evaluate("Sunday2026!")).isEqualTo(PasswordPolicy.Result.OK);
    }

    @Test
    void rejectsTooShort() {
        assertThat(policy.evaluate("Aa1xxx"))
                .isEqualTo(PasswordPolicy.Result.LENGTH_FAIL);
    }

    @Test
    void rejectsBreached() {
        assertThat(policy.evaluate("Password1!"))
                .isEqualTo(PasswordPolicy.Result.BREACHED);
        assertThat(policy.evaluate("welcome1"))
                .isEqualTo(PasswordPolicy.Result.LENGTH_FAIL); // also < 10 chars
    }

    @Test
    void rejectsMissingMixedCase() {
        assertThat(policy.evaluate("alllowercase1"))
                .isEqualTo(PasswordPolicy.Result.NEEDS_MIXED_CASE);
    }

    @Test
    void rejectsMissingDigit() {
        assertThat(policy.evaluate("MixedCaseNoDigit"))
                .isEqualTo(PasswordPolicy.Result.NEEDS_DIGIT);
    }

    @Test
    void honoursSymbolToggleWhenEnabled() {
        var symbolRequired = new PasswordPolicy(10, 128, true, true, true);
        assertThat(symbolRequired.evaluate("MixedCase1234"))
                .isEqualTo(PasswordPolicy.Result.NEEDS_SYMBOL);
        assertThat(symbolRequired.evaluate("MixedCase1234!"))
                .isEqualTo(PasswordPolicy.Result.OK);
    }
}
