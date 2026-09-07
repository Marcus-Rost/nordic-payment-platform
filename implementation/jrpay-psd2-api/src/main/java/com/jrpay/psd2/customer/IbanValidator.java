package com.jrpay.psd2.customer;

/** Lightweight ISO 13616 mod-97 IBAN check. */
public final class IbanValidator {

    private IbanValidator() {}

    public static boolean isValid(String iban) {
        if (iban == null) return false;
        String compact = iban.replace(" ", "").toUpperCase();
        if (!compact.matches("^[A-Z]{2}[0-9A-Z]{13,30}$")) return false;
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c >= 'A' && c <= 'Z') digits.append(10 + (c - 'A'));
            else return false;
        }
        long mod = 0;
        for (int i = 0; i < digits.length(); i++) {
            mod = (mod * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return mod == 1;
    }

    public static String compact(String iban) {
        return iban == null ? null : iban.replace(" ", "").toUpperCase();
    }

    public static String mask(String iban) {
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
}
