package com.jrpay.vop.matching;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for the name matching algorithm.
 * Generates thousands of random name pairs and verifies invariants.
 */
class NameMatcherPropertyTest {

    private final NameMatcher matcher = new NameMatcher(0.95, 0.85);

    @Property(tries = 500)
    @Label("Matching is symmetric: score(A,B) == score(B,A)")
    void symmetric(@ForAll("names") String a, @ForAll("names") String b) {
        var ab = matcher.match(a, b);
        var ba = matcher.match(b, a);
        assertEquals(ab.similarityScore(), ba.similarityScore(), 0.001,
                "Asymmetry for '" + a + "' vs '" + b + "': " +
                        ab.similarityScore() + " vs " + ba.similarityScore());
    }

    @Property(tries = 500)
    @Label("Score is always in [0.0, 1.0]")
    void scoreBounded(@ForAll("names") String a, @ForAll("names") String b) {
        var result = matcher.match(a, b);
        assertTrue(result.similarityScore() >= 0.0,
                "Score below 0: " + result.similarityScore());
        assertTrue(result.similarityScore() <= 1.0,
                "Score above 1: " + result.similarityScore());
    }

    @Property(tries = 200)
    @Label("Identity: score(x, x) == 1.0")
    void identity(@ForAll("names") String name) {
        var result = matcher.match(name, name);
        assertEquals(1.0, result.similarityScore(), 0.001,
                "Self-match not 1.0 for '" + name + "'");
    }

    @Property(tries = 500)
    @Label("Normalization is idempotent: normalize(normalize(x)) == normalize(x)")
    void normalizationIdempotent(@ForAll("rawNames") String name) {
        String once = NameNormalizer.normalize(name);
        String twice = NameNormalizer.normalize(once);
        assertEquals(once, twice, "Not idempotent for: '" + name + "'");
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.of(
                "Ola Nordmann", "Kari Hansen", "Jan Erik",
                "Bjørn Nilsen", "Åse Johansen", "van der Berg",
                "Grønn Energi AS", "Nordmann Ola", "Mueller GmbH",
                "Per", "A", "Smith Ltd", "de la Cruz",
                "Ødegård", "Erik von Müller", "Tietoevry AB"
        );
    }

    @Provide
    Arbitrary<String> rawNames() {
        // Include more edge cases for normalization
        return Arbitraries.of(
                "Bjørn", "UPPER CASE", "  spaces  ", "Ølaf Ødegård",
                "Müller-Schmidt", "O'Brien", "José García",
                "Ää Öö Üü", "", "x", "Normal Name"
        );
    }
}
