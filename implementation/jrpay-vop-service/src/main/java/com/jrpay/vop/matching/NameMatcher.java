package com.jrpay.vop.matching;

import com.jrpay.domain.payment.VoPResult;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Structural name matching engine per architecture decision C2.
 * Pipeline: normalize → decompose → tokenize → best-permutation Jaro-Winkler.
 */
@Component
public class NameMatcher {

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    /** Compound prefixes kept with the following token. */
    private static final Set<String> COMPOUND_PREFIXES = Set.of(
            "van", "der", "de", "von", "af", "la", "le", "di", "del"
    );

    /** Maximum tokens for permutation (5! = 120 permutations). */
    private static final int MAX_PERMUTATION_TOKENS = 5;

    private final double matchThreshold;
    private final double closeMatchThreshold;

    public NameMatcher(
            @Value("${jrpay.vop.matching.match-threshold:0.95}") double matchThreshold,
            @Value("${jrpay.vop.matching.close-match-threshold:0.85}") double closeMatchThreshold) {
        this.matchThreshold = matchThreshold;
        this.closeMatchThreshold = closeMatchThreshold;
    }

    /**
     * Match two names and return the result with similarity score.
     */
    public MatchResult match(String statedName, String actualName) {
        // Step 1: Normalize
        String normalizedStated = NameNormalizer.normalize(statedName);
        String normalizedActual = NameNormalizer.normalize(actualName);

        if (normalizedStated.isEmpty() || normalizedActual.isEmpty()) {
            return new MatchResult(VoPResult.NO_MATCH, 0.0);
        }

        // Step 2: Decompose (separate legal entity suffix)
        LegalEntityDetector.Decomposition statedDecomp = LegalEntityDetector.decompose(normalizedStated);
        LegalEntityDetector.Decomposition actualDecomp = LegalEntityDetector.decompose(normalizedActual);

        // Step 3: Tokenize base names
        List<String> statedTokens = tokenize(statedDecomp.baseName());
        List<String> actualTokens = tokenize(actualDecomp.baseName());

        // Step 4: Best-permutation Jaro-Winkler on base name
        double baseScore = bestPermutationScore(statedTokens, actualTokens);

        // Step 5: Combine with suffix handling
        double finalScore = combineScores(baseScore, statedDecomp, actualDecomp);

        // Step 6: Apply thresholds
        VoPResult result;
        if (finalScore >= matchThreshold) {
            result = VoPResult.MATCH;
        } else if (finalScore >= closeMatchThreshold) {
            result = VoPResult.CLOSE_MATCH;
        } else {
            result = VoPResult.NO_MATCH;
        }

        return new MatchResult(result, finalScore);
    }

    /**
     * Tokenize with compound prefix handling.
     * "van der berg" → ["van der berg"]
     * "jan erik van der berg" → ["jan", "erik", "van der berg"]
     */
    List<String> tokenize(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return List.of();
        }

        String[] words = normalizedName.split("\\s+");
        List<String> tokens = new ArrayList<>();
        int i = 0;

        while (i < words.length) {
            if (COMPOUND_PREFIXES.contains(words[i]) && i + 1 < words.length) {
                // Collect compound prefix + following tokens
                StringBuilder compound = new StringBuilder(words[i]);
                i++;
                while (i < words.length && COMPOUND_PREFIXES.contains(words[i])) {
                    compound.append(" ").append(words[i]);
                    i++;
                }
                if (i < words.length) {
                    compound.append(" ").append(words[i]);
                    i++;
                }
                tokens.add(compound.toString());
            } else {
                tokens.add(words[i]);
                i++;
            }
        }

        return tokens;
    }

    /**
     * Compute best Jaro-Winkler score across permutations of BOTH token lists.
     * This ensures symmetry: bestPermutationScore(A, B) == bestPermutationScore(B, A).
     */
    double bestPermutationScore(List<String> tokensA, List<String> tokensB) {
        // Permute both directions and take the max — guarantees symmetry
        double scoreAB = bestScoreOneDirection(tokensA, tokensB);
        double scoreBA = bestScoreOneDirection(tokensB, tokensA);
        return Math.max(scoreAB, scoreBA);
    }

    private double bestScoreOneDirection(List<String> permuteTokens, List<String> fixedTokens) {
        String fixedJoined = String.join(" ", fixedTokens);

        if (permuteTokens.size() > MAX_PERMUTATION_TOKENS) {
            return JARO_WINKLER.apply(String.join(" ", permuteTokens), fixedJoined);
        }

        double bestScore = 0.0;
        for (List<String> perm : permutations(permuteTokens)) {
            String permJoined = String.join(" ", perm);
            double score = JARO_WINKLER.apply(permJoined, fixedJoined);
            bestScore = Math.max(bestScore, score);
        }
        return bestScore;
    }

    /**
     * Combine base score with suffix matching logic.
     */
    private double combineScores(double baseScore,
                                 LegalEntityDetector.Decomposition stated,
                                 LegalEntityDetector.Decomposition actual) {
        boolean statedHasSuffix = stated.isLegalEntity();
        boolean actualHasSuffix = actual.isLegalEntity();

        if (statedHasSuffix && actualHasSuffix) {
            // Both have suffix — check if they match
            boolean suffixMatch = stated.suffix().equals(actual.suffix());
            return suffixMatch ? baseScore : baseScore * 0.9;
        } else if (statedHasSuffix != actualHasSuffix) {
            // One has suffix, other doesn't — slight penalty
            return baseScore * 0.95;
        } else {
            // Neither has suffix (person names) — use base score directly
            return baseScore;
        }
    }

    /**
     * Generate all permutations of a list (up to MAX_PERMUTATION_TOKENS elements).
     */
    static <T> List<List<T>> permutations(List<T> list) {
        if (list.size() <= 1) {
            return List.of(list);
        }

        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            T element = list.get(i);
            List<T> rest = new ArrayList<>(list);
            rest.remove(i);
            for (List<T> perm : permutations(rest)) {
                List<T> full = new ArrayList<>();
                full.add(element);
                full.addAll(perm);
                result.add(full);
            }
        }
        return result;
    }

    /**
     * Result of a name match operation.
     */
    public record MatchResult(VoPResult result, double similarityScore) {}
}
