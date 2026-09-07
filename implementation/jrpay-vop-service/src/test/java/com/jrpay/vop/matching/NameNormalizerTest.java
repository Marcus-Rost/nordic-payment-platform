package com.jrpay.vop.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for name normalization — the foundation of the matching pipeline.
 */
class NameNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @DisplayName("Diacritics normalization (EPC288-23)")
    @CsvSource({
            "Bjørn Nilsen, bjorn nilsen",
            "Åse Johansen, ase johansen",
            "Ødegård, odegard",
            "Grønn Energi, gronn energi",
            "Müller, muller",
            "René, rene",
            "Ólafur, olafur"
    })
    void diacriticsNormalization(String input, String expected) {
        assertEquals(expected, NameNormalizer.normalize(input));
    }

    @Test
    @DisplayName("Case insensitive")
    void caseInsensitive() {
        assertEquals("ola nordmann", NameNormalizer.normalize("Ola NORDMANN"));
    }

    @Test
    @DisplayName("Whitespace collapse and trim")
    void whitespaceCollapse() {
        assertEquals("ola nordmann", NameNormalizer.normalize("  Ola   Nordmann  "));
    }

    @Test
    @DisplayName("Non-alphabetic characters removed")
    void nonAlphaRemoved() {
        assertEquals("ola nordmann", NameNormalizer.normalize("Ola, Nordmann!"));
    }

    @Test
    @DisplayName("Normalization is idempotent")
    void idempotent() {
        String name = "Bjørn Ødegård AS";
        String once = NameNormalizer.normalize(name);
        String twice = NameNormalizer.normalize(once);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("Empty and null handled")
    void emptyAndNull() {
        assertEquals("", NameNormalizer.normalize(null));
        assertEquals("", NameNormalizer.normalize(""));
        assertEquals("", NameNormalizer.normalize("   "));
    }
}
