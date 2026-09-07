package com.jrpay.vop.matching;

import com.jrpay.domain.payment.VoPResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the structural name matching engine.
 * Tests the complete pipeline: normalize → decompose → tokenize → best-permutation JW.
 */
class NameMatcherTest {

    private NameMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new NameMatcher(0.95, 0.85);
    }

    // === Exact matches ===

    @Test
    @DisplayName("Exact same name → MATCH")
    void exactMatch() {
        var result = matcher.match("Ola Nordmann", "Ola Nordmann");
        assertEquals(VoPResult.MATCH, result.result());
        assertEquals(1.0, result.similarityScore(), 0.001);
    }

    @Test
    @DisplayName("Diacritics normalized → MATCH (Bjørn = Bjorn)")
    void diacriticsMatch() {
        var result = matcher.match("Bjørn Nilsen", "Bjorn Nilsen");
        assertEquals(VoPResult.MATCH, result.result());
    }

    @Test
    @DisplayName("Name order permutation → MATCH (Nordmann, Ola = Ola Nordmann)")
    void nameOrderMatch() {
        var result = matcher.match("Nordmann Ola", "Ola Nordmann");
        assertEquals(VoPResult.MATCH, result.result());
    }

    @Test
    @DisplayName("Compound name order → MATCH (Jan Erik van der Berg = van der Berg, Jan Erik)")
    void compoundNameOrder() {
        var result = matcher.match("Jan Erik van der Berg", "van der Berg Jan Erik");
        assertEquals(VoPResult.MATCH, result.result());
    }

    // === Close matches ===

    @Test
    @DisplayName("Typo → high score (Ola Nordman vs Ola Nordmann)")
    void typoHighScore() {
        var result = matcher.match("Ola Nordman", "Ola Nordmann");
        // "ola nordman" vs "ola nordmann" — JW score is very high (~0.97)
        // Whether this is MATCH or CLOSE_MATCH depends on exact JW calculation
        assertTrue(result.similarityScore() >= 0.85,
                "Score should be >= 0.85, was " + result.similarityScore());
    }

    @Test
    @DisplayName("Missing suffix → score >= 0.85 (Ola Nordmann vs Ola Nordmann AS)")
    void missingSuffixCloseMatch() {
        var result = matcher.match("Ola Nordmann", "Ola Nordmann AS");
        // Base score is 1.0, missing suffix penalty * 0.95 = 0.95 → MATCH
        assertTrue(result.similarityScore() >= 0.85,
                "Score should be >= 0.85, was " + result.similarityScore());
    }

    // === No matches ===

    @Test
    @DisplayName("Completely different name → NO_MATCH")
    void noMatch() {
        var result = matcher.match("Ola Nordmann", "Kari Hansen");
        assertEquals(VoPResult.NO_MATCH, result.result());
        assertTrue(result.similarityScore() < 0.85);
    }

    // === Legal entity handling ===

    @Test
    @DisplayName("Same name, same suffix → MATCH")
    void legalEntitySameSuffix() {
        var result = matcher.match("Gronn Energi AS", "Gronn Energi AS");
        assertEquals(VoPResult.MATCH, result.result());
    }

    @Test
    @DisplayName("Same name, expanded suffix → MATCH (AS = Aksjeselskap)")
    void legalEntityExpandedSuffix() {
        var result = matcher.match("Gronn Energi AS", "Gronn Energi Aksjeselskap");
        assertEquals(VoPResult.MATCH, result.result());
    }

    @Test
    @DisplayName("Diacritics + suffix expansion → MATCH (Grønn Energi AS = Gronn Energi Aksjeselskap)")
    void diacriticsPlusSuffixExpansion() {
        var result = matcher.match("Grønn Energi AS", "Gronn Energi Aksjeselskap");
        assertEquals(VoPResult.MATCH, result.result());
    }

    // === Tokenization ===

    @Test
    @DisplayName("Compound prefix tokenization")
    void compoundPrefixTokenization() {
        var tokens = matcher.tokenize("jan erik van der berg");
        assertEquals(3, tokens.size()); // ["jan", "erik", "van der berg"]
        assertEquals("van der berg", tokens.get(2));
    }

    @Test
    @DisplayName("Simple tokenization")
    void simpleTokenization() {
        var tokens = matcher.tokenize("ola nordmann");
        assertEquals(2, tokens.size());
    }

    // === Edge cases ===

    @Test
    @DisplayName("Single word names")
    void singleWordNames() {
        var result = matcher.match("Nordmann", "Nordmann");
        assertEquals(VoPResult.MATCH, result.result());
    }

    @Test
    @DisplayName("Empty input → NO_MATCH")
    void emptyInput() {
        var result = matcher.match("", "Ola Nordmann");
        assertEquals(VoPResult.NO_MATCH, result.result());
    }

    @Test
    @DisplayName("Score is symmetric: match(A,B) == match(B,A)")
    void symmetry() {
        var ab = matcher.match("Ola Nordmann", "Nordmann Ola");
        var ba = matcher.match("Nordmann Ola", "Ola Nordmann");
        assertEquals(ab.similarityScore(), ba.similarityScore(), 0.001);
    }

    @Test
    @DisplayName("Score is bounded [0, 1]")
    void scoreBounded() {
        var result = matcher.match("abc xyz", "completely different name here");
        assertTrue(result.similarityScore() >= 0.0);
        assertTrue(result.similarityScore() <= 1.0);
    }
}
