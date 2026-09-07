package com.jrpay.domain.money;

/**
 * Supported currencies with ISO 4217 metadata.
 * Scale (minor units) is derived from the standard, never hardcoded elsewhere.
 */
public enum Currency {

    NOK("578", 2, "Norwegian Krone"),
    EUR("978", 2, "Euro"),
    SEK("752", 2, "Swedish Krona"),
    DKK("208", 2, "Danish Krone"),
    USD("840", 2, "US Dollar");

    private final String numericCode;
    private final int minorUnits;
    private final String displayName;

    Currency(String numericCode, int minorUnits, String displayName) {
        this.numericCode = numericCode;
        this.minorUnits = minorUnits;
        this.displayName = displayName;
    }

    /**
     * ISO 4217 minor unit count. Determines decimal scale for this currency.
     * EUR/NOK/SEK/DKK/USD = 2. JPY would be 0. BHD would be 3.
     */
    public int getMinorUnits() {
        return minorUnits;
    }

    /** ISO 4217 numeric code (e.g., "978" for EUR). */
    public String getNumericCode() {
        return numericCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parse from ISO 4217 alphabetic code. Throws if unsupported.
     */
    public static Currency fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Currency code must not be null or blank");
        }
        try {
            return valueOf(code.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported currency code: '" + code + "'. Supported: NOK, EUR, SEK, DKK, USD");
        }
    }
}
