package com.jrpay.ledger.admin;

/**
 * Masking helpers for admin views (UC-G35 §12, UC-G36).
 */
public final class Masking {

    private Masking() {}

    /**
     * "Ola Nordmann" → "Ola N*****" — keep the first word, the first letter
     * of the next, then mask the rest of that word with bullets matching its
     * length. Subsequent words are dropped.
     */
    public static String holderName(String full) {
        if (full == null) return null;
        String trimmed = full.trim();
        if (trimmed.isEmpty()) return "";
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            // Single word — show first 2 chars then mask
            if (trimmed.length() <= 2) return trimmed;
            return trimmed.substring(0, 2) + "*".repeat(Math.max(1, trimmed.length() - 2));
        }
        String first = trimmed.substring(0, firstSpace);
        String rest = trimmed.substring(firstSpace + 1).trim();
        int firstWordEnd = rest.indexOf(' ');
        String second = firstWordEnd < 0 ? rest : rest.substring(0, firstWordEnd);
        if (second.isEmpty()) return first;
        char initial = second.charAt(0);
        int masked = Math.max(1, second.length() - 1);
        return first + " " + initial + "*".repeat(masked);
    }

    /** "NO9386011117947" → "NO93 •••• •••• 7947" (UC-G35 §12). */
    public static String iban(String iban) {
        if (iban == null) return null;
        String compact = iban.replace(" ", "");
        if (compact.length() < 8) return compact;
        String first4 = compact.substring(0, 4);
        String last4 = compact.substring(compact.length() - 4);
        int middle = compact.length() - 8;
        StringBuilder mask = new StringBuilder(first4 + " ");
        // group middle into 4-char clusters
        int filled = 0;
        while (filled < middle) {
            int chunk = Math.min(4, middle - filled);
            for (int i = 0; i < chunk; i++) mask.append('•');
            mask.append(' ');
            filled += chunk;
        }
        mask.append(last4);
        return mask.toString().trim();
    }

    /** Address mask: keep first line city/region; bullet out street/number. */
    public static String address(String full) {
        if (full == null) return null;
        String trimmed = full.trim();
        if (trimmed.isEmpty()) return "";
        // Heuristic: split on comma; mask before, keep after.
        int comma = trimmed.indexOf(',');
        if (comma < 0) {
            return trimmed.length() <= 4
                    ? "•".repeat(trimmed.length())
                    : "•".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
        }
        return "•".repeat(comma) + trimmed.substring(comma);
    }

    /** "12345678901" → "•••••••8901" — last 4 visible. */
    public static String fodselsnummer(String fnr) {
        if (fnr == null) return null;
        if (fnr.length() <= 4) return fnr;
        return "•".repeat(fnr.length() - 4) + fnr.substring(fnr.length() - 4);
    }
}
