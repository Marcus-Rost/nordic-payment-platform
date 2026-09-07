package com.jrpay.fraud.sanctions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the three-tier sanctions screening index.
 */
class SanctionsIndexTest {

    private SanctionsIndex index;

    @BeforeEach
    void setUp() {
        index = new SanctionsIndex();
        index.loadList("TEST-v1", List.of(
                "Osama Bin Laden",
                "Al-Qaeda Foundation",
                "Kim Jong Un",
                "Islamic State",
                "Hezbollah"
        ));
    }

    @Test
    @DisplayName("Exact match → HIT")
    void exactMatch() {
        var result = index.screen("Osama Bin Laden");
        assertTrue(result.isHit());
        assertEquals("Osama Bin Laden", result.matchedEntry());
    }

    @Test
    @DisplayName("Case-insensitive match → HIT")
    void caseInsensitive() {
        var result = index.screen("osama bin laden");
        assertTrue(result.isHit());
    }

    @Test
    @DisplayName("Diacritics normalized → HIT (Hézbollah = Hezbollah)")
    void diacriticsNormalized() {
        var result = index.screen("Hézbollah");
        assertTrue(result.isHit());
    }

    @Test
    @DisplayName("Fuzzy match → HIT (very close spelling variant)")
    void fuzzyMatch() {
        // "Hezbollahh" (extra 'h') vs "Hezbollah" — close enough for JW > 0.85
        var result = index.screen("Hezbollahh");
        assertTrue(result.isHit(), "Fuzzy match should detect very close variant");
    }

    @Test
    @DisplayName("No match → CLEAR")
    void noMatch() {
        var result = index.screen("Ola Nordmann");
        assertFalse(result.isHit());
    }

    @Test
    @DisplayName("Empty name → CLEAR")
    void emptyName() {
        var result = index.screen("");
        assertFalse(result.isHit());
    }

    @Test
    @DisplayName("Null name → CLEAR")
    void nullName() {
        var result = index.screen(null);
        assertFalse(result.isHit());
    }

    @Test
    @DisplayName("List version tracked")
    void listVersion() {
        assertEquals("TEST-v1", index.getListVersion());
    }

    @Test
    @DisplayName("Atomic list update — new version replaces old")
    void atomicUpdate() {
        index.loadList("TEST-v2", List.of("New Sanctioned Entity"));

        assertFalse(index.screen("Osama Bin Laden").isHit()); // old entry gone
        assertTrue(index.screen("New Sanctioned Entity").isHit());
        assertEquals("TEST-v2", index.getListVersion());
    }

    @Test
    @DisplayName("Normal names do not match (no false positives on common names)")
    void noFalsePositivesOnCommonNames() {
        assertFalse(index.screen("Kari Hansen").isHit());
        assertFalse(index.screen("Jan Erik van der Berg").isHit());
        assertFalse(index.screen("Grønn Energi AS").isHit());
        assertFalse(index.screen("Bjørn Nilsen").isHit());
    }
}
