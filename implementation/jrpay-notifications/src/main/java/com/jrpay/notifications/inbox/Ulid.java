package com.jrpay.notifications.inbox;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Crockford-Base32 encoded ULID (26 chars). Monotonic across instances at
 * millisecond resolution + 80 bits of entropy. Used as the inbox row PK so
 * the SSE stream's {@code Last-Event-ID} can pick up where it left off.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String generate() {
        return generate(Instant.now());
    }

    public static String generate(Instant at) {
        long ts = at.toEpochMilli();
        char[] out = new char[26];
        // 48-bit timestamp → 10 chars.
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        // 80-bit randomness → 16 chars.
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        long hi = ((long) (r[0] & 0xff) << 32)
                | ((long) (r[1] & 0xff) << 24)
                | ((long) (r[2] & 0xff) << 16)
                | ((long) (r[3] & 0xff) << 8)
                | (r[4] & 0xff);
        long lo = ((long) (r[5] & 0xff) << 32)
                | ((long) (r[6] & 0xff) << 24)
                | ((long) (r[7] & 0xff) << 16)
                | ((long) (r[8] & 0xff) << 8)
                | (r[9] & 0xff);
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 0x1F)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 0x1F)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
