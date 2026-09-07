package com.jrpay.fraud.casework;

public final class Masking {

    private Masking() {}

    public static String iban(String iban) {
        if (iban == null) return null;
        String compact = iban.replace(" ", "");
        if (compact.length() < 8) return compact;
        String first4 = compact.substring(0, 4);
        String last4 = compact.substring(compact.length() - 4);
        int middle = compact.length() - 8;
        StringBuilder mask = new StringBuilder(first4 + " ");
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

    public static String holderName(String full) {
        if (full == null) return null;
        String trimmed = full.trim();
        if (trimmed.isEmpty()) return "";
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
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
}
