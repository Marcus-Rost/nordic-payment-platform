package com.jrpay.fraud.sanctions;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Three-tier sanctions screening index.
 * Tier 1: Bloom filter (fast pre-filter, may have false positives)
 * Tier 2: Exact match (normalized name lookup)
 * Tier 3: Fuzzy match (Jaro-Winkler for transliteration variants)
 *
 * The index is swapped atomically via AtomicReference when the list is updated.
 */
@Component
public class SanctionsIndex {

    private static final Logger log = LoggerFactory.getLogger(SanctionsIndex.class);
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final double FUZZY_THRESHOLD = 0.85;
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}");

    private final AtomicReference<SanctionsSnapshot> snapshot = new AtomicReference<>(
            new SanctionsSnapshot("EMPTY-v0", Collections.emptySet(), null, Collections.emptyList()));

    /**
     * Load a sanctions list. Replaces the current index atomically.
     */
    @SuppressWarnings("UnstableApiUsage")
    public void loadList(String version, List<String> sanctionedNames) {
        Set<String> normalizedNames = new HashSet<>();
        List<String> originalNames = new ArrayList<>();

        BloomFilter<CharSequence> bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                Math.max(sanctionedNames.size(), 100),
                0.01 // 1% false positive rate
        );

        for (String name : sanctionedNames) {
            String normalized = normalize(name);
            normalizedNames.add(normalized);
            originalNames.add(name);
            bloomFilter.put(normalized);
        }

        SanctionsSnapshot newSnapshot = new SanctionsSnapshot(
                version, normalizedNames, bloomFilter, originalNames);
        snapshot.set(newSnapshot);

        log.info("Sanctions index loaded: version={}, entries={}", version, sanctionedNames.size());
    }

    /**
     * Screen a name against the sanctions index.
     */
    @SuppressWarnings("UnstableApiUsage")
    public ScreeningResult screen(String name) {
        if (name == null || name.isBlank()) {
            return ScreeningResult.clear();
        }

        SanctionsSnapshot current = snapshot.get();
        String normalized = normalize(name);

        // Tier 1: Bloom filter pre-check (fast negative for exact match only)
        if (current.bloomFilter() != null && current.bloomFilter().mightContain(normalized)) {
            // Tier 2: Possible exact match — verify
            if (current.normalizedNames().contains(normalized)) {
                String original = findOriginal(current, normalized);
                return ScreeningResult.hit(original);
            }
        }

        // Tier 3: Fuzzy match (Jaro-Winkler) — always runs for non-exact matches
        // This catches transliteration variants, typos, and close spellings
        for (String sanctioned : current.normalizedNames()) {
            double similarity = JARO_WINKLER.apply(normalized, sanctioned);
            if (similarity >= FUZZY_THRESHOLD) {
                String original = findOriginal(current, sanctioned);
                return ScreeningResult.hit(original);
            }
        }

        return ScreeningResult.clear();
    }

    public String getListVersion() {
        return snapshot.get().version();
    }

    private String findOriginal(SanctionsSnapshot snap, String normalized) {
        for (String original : snap.originalNames()) {
            if (normalize(original).equals(normalized)) {
                return original;
            }
        }
        return normalized;
    }

    static String normalize(String name) {
        if (name == null) return "";
        // NFD + strip marks + lowercase + collapse spaces
        String nfd = Normalizer.normalize(name, Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(nfd).replaceAll("");
        return stripped.toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("UnstableApiUsage")
    record SanctionsSnapshot(
            String version,
            Set<String> normalizedNames,
            BloomFilter<CharSequence> bloomFilter,
            List<String> originalNames
    ) {}

    public record ScreeningResult(boolean isHit, String matchedEntry) {
        public static ScreeningResult clear() {
            return new ScreeningResult(false, null);
        }

        public static ScreeningResult hit(String matchedEntry) {
            return new ScreeningResult(true, matchedEntry);
        }
    }
}
