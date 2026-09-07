package com.jrpay.notifications.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void generatesTwentySixCrockfordChars() {
        String ulid = Ulid.generate();
        assertThat(ulid).hasSize(26).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
    }

    @Test
    void monotonicAcrossSequentialMillisecondCalls() throws InterruptedException {
        String prev = Ulid.generate(Instant.parse("2026-04-25T00:00:00Z"));
        for (int i = 0; i < 100; i++) {
            Thread.sleep(2);
            String next = Ulid.generate();
            assertThat(next.compareTo(prev))
                    .as("ULID at iteration %s should be ≥ predecessor", i)
                    .isGreaterThanOrEqualTo(0);
            prev = next;
        }
    }

    @Test
    void thousandGenerationsAllUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(Ulid.generate());
        }
        assertThat(seen).hasSize(1_000);
    }
}
