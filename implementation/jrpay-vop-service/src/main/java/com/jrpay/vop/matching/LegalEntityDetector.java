package com.jrpay.vop.matching;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and separates legal entity suffixes from base names.
 * Handles Nordic and European legal forms: AS, ASA, Ltd, GmbH, AB, etc.
 */
public final class LegalEntityDetector {

    /** Map of abbreviations to their full expansions (normalized). */
    private static final Map<String, String> SUFFIX_EXPANSIONS = Map.ofEntries(
            Map.entry("as", "aksjeselskap"),
            Map.entry("asa", "allmennaksjeselskap"),
            Map.entry("ltd", "limited"),
            Map.entry("limited", "limited"),
            Map.entry("gmbh", "gesellschaft mit beschrankter haftung"),
            Map.entry("ab", "aktiebolag"),
            Map.entry("sa", "societe anonyme"),
            Map.entry("nv", "naamloze vennootschap"),
            Map.entry("bv", "besloten vennootschap"),
            Map.entry("ag", "aktiengesellschaft"),
            Map.entry("se", "societas europaea"),
            Map.entry("aksjeselskap", "aksjeselskap"),
            Map.entry("allmennaksjeselskap", "allmennaksjeselskap"),
            Map.entry("aktiebolag", "aktiebolag")
    );

    /** All known suffixes (for pattern matching at end of name). */
    private static final Set<String> KNOWN_SUFFIXES = SUFFIX_EXPANSIONS.keySet();

    private LegalEntityDetector() {}

    /**
     * Decompose a normalized name into base name and legal suffix.
     *
     * @param normalizedName the name after normalization
     * @return decomposition result with base name and optional suffix
     */
    public static Decomposition decompose(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return new Decomposition(normalizedName, null);
        }

        String[] words = normalizedName.split("\\s+");
        if (words.length < 2) {
            return new Decomposition(normalizedName, null);
        }

        // Check last word (or last two words for multi-word suffixes)
        String lastWord = words[words.length - 1];
        if (KNOWN_SUFFIXES.contains(lastWord)) {
            String baseName = normalizedName.substring(0,
                    normalizedName.length() - lastWord.length()).trim();
            String expandedSuffix = SUFFIX_EXPANSIONS.get(lastWord);
            return new Decomposition(baseName, expandedSuffix);
        }

        // Check first word (some names put the suffix first, e.g., "AS Nordmann")
        String firstWord = words[0];
        if (KNOWN_SUFFIXES.contains(firstWord)) {
            String baseName = normalizedName.substring(firstWord.length()).trim();
            String expandedSuffix = SUFFIX_EXPANSIONS.get(firstWord);
            return new Decomposition(baseName, expandedSuffix);
        }

        return new Decomposition(normalizedName, null);
    }

    /**
     * Result of legal entity decomposition.
     *
     * @param baseName the name without the legal suffix
     * @param suffix the expanded legal suffix (null if not a legal entity)
     */
    public record Decomposition(String baseName, String suffix) {

        public boolean isLegalEntity() {
            return suffix != null;
        }
    }
}
