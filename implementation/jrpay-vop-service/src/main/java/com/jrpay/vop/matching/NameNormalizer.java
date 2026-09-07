package com.jrpay.vop.matching;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalizes names for comparison per EPC288-23 recommendations.
 * Pipeline: Unicode NFD → strip combining marks → EPC equivalences →
 * lowercase → collapse whitespace → remove non-alphanumeric.
 */
public final class NameNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}");
    private static final Pattern NON_ALPHA_NUMERIC = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /**
     * Characters that NFD decomposition does NOT handle — these are separate
     * Unicode code points, not base letter + combining mark.
     * Must be replaced explicitly before NFD processing.
     */
    private static final Map<String, String> PRE_NFD_REPLACEMENTS = Map.ofEntries(
            Map.entry("ø", "o"), Map.entry("Ø", "O"),
            Map.entry("æ", "ae"), Map.entry("Æ", "AE"),
            Map.entry("đ", "d"), Map.entry("Đ", "D"),
            Map.entry("ł", "l"), Map.entry("Ł", "L")
    );

    /**
     * EPC288-23 equivalence rules for digraphs.
     * Applied AFTER NFD decomposition + mark stripping.
     */
    private static final Map<String, String> EPC_EQUIVALENCES = Map.ofEntries(
            Map.entry("oe", "o"),
            Map.entry("ae", "a"),
            Map.entry("aa", "a"),
            Map.entry("ue", "u"),
            Map.entry("ee", "e")
    );

    private NameNormalizer() {}

    /**
     * Normalize a name for comparison.
     * Idempotent: normalize(normalize(x)) == normalize(x).
     */
    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        // Step 0: Replace characters that NFD doesn't decompose (ø, æ, đ, ł)
        String preProcessed = name;
        for (Map.Entry<String, String> entry : PRE_NFD_REPLACEMENTS.entrySet()) {
            preProcessed = preProcessed.replace(entry.getKey(), entry.getValue());
        }

        // Step 1: Unicode NFD decomposition → strip combining marks
        // This converts å→a, ä→a, ü→u, é→e, etc.
        String nfd = Normalizer.normalize(preProcessed, Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(nfd).replaceAll("");

        // Step 2: Lowercase
        String lower = stripped.toLowerCase();

        // Step 3: Apply EPC digraph equivalences (oe→o, ae→a, aa→a, ue→u, ee→e)
        String equivalent = lower;
        for (Map.Entry<String, String> entry : EPC_EQUIVALENCES.entrySet()) {
            equivalent = equivalent.replace(entry.getKey(), entry.getValue());
        }

        // Step 4: Remove non-alphanumeric (keep letters and digits and spaces)
        String cleaned = NON_ALPHA_NUMERIC.matcher(equivalent).replaceAll("");

        // Step 5: Collapse whitespace and trim
        return MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();
    }
}
