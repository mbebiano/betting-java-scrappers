package com.superodds.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NormalizationUtils.
 */
class NormalizationUtilsTest {

    @Test
    void testNormalizeText() {
        // Test uppercase conversion
        assertEquals("GREMIO", NormalizationUtils.normalizeText("gremio"));
        
        // Test accent removal
        assertEquals("GREMIO", NormalizationUtils.normalizeText("Grêmio"));
        
        // Test multiple transformations
        assertEquals("FLUMINENSE", NormalizationUtils.normalizeText("Fluminense"));
        
        // Test space to underscore
        assertEquals("SAO_PAULO", NormalizationUtils.normalizeText("São Paulo"));
        
        // Test special character removal
        assertEquals("ATLETICO_MINEIRO", NormalizationUtils.normalizeText("Atlético-Mineiro"));
        
        // Test collapse multiple underscores
        assertEquals("TEST_VALUE", NormalizationUtils.normalizeText("test  value"));
        
        // Test leading/trailing removal
        assertEquals("VALUE", NormalizationUtils.normalizeText(" value "));
    }

    @Test
    void testGenerateNormalizedId() {
        // Create test instant for 2025-12-03 00:30:00 UTC
        Instant testDate = Instant.parse("2025-12-03T00:30:00Z");
        
        String normalizedId = NormalizationUtils.generateNormalizedId(
            "Futebol", 
            testDate, 
            "Grêmio", 
            "Fluminense"
        );
        
        assertEquals("FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE", normalizedId);
    }

    @Test
    void testGenerateNormalizedIdWithSpaces() {
        Instant testDate = Instant.parse("2025-12-03T15:00:00Z");
        
        String normalizedId = NormalizationUtils.generateNormalizedId(
            "Futebol", 
            testDate, 
            "São Paulo", 
            "Atlético Mineiro"
        );
        
        assertEquals("FUTEBOL-20251203T150000Z-SAO_PAULO-ATLETICO_MINEIRO", normalizedId);
    }

    @Test
    void testNormalizeTextWithNullOrEmpty() {
        assertEquals("", NormalizationUtils.normalizeText(null));
        assertEquals("", NormalizationUtils.normalizeText(""));
        assertEquals("", NormalizationUtils.normalizeText("   "));
    }
}
