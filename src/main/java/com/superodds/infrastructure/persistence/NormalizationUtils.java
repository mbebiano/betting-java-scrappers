package com.superodds.infrastructure.persistence;

import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Utilities for event normalization following the contract rules.
 */
public class NormalizationUtils {

    private static final DateTimeFormatter NORMALIZED_DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /**
     * Normalizes text for use in normalized IDs.
     * 
     * Rules:
     * 1. Convert to uppercase
     * 2. Remove accents (Grêmio -> GREMIO)
     * 3. Replace non-alphanumeric with underscore
     * 4. Collapse multiple underscores
     * 5. Remove leading/trailing underscores
     */
    public static String normalizeText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        
        // Remove accents
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        
        // Convert to uppercase
        normalized = normalized.toUpperCase();
        
        // Replace non-alphanumeric with underscore
        normalized = normalized.replaceAll("[^A-Z0-9]+", "_");
        
        // Collapse multiple underscores
        normalized = normalized.replaceAll("_+", "_");
        
        // Remove leading/trailing underscores
        normalized = normalized.replaceAll("^_+|_+$", "");
        
        return normalized;
    }

    /**
     * Generates a normalized ID from event components.
     * 
     * Format: <SPORT>-<DATETIME_UTC>-<HOME>-<AWAY>
     * Example: FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE
     */
    public static String generateNormalizedId(String sport, Instant startDate, String home, String away) {
        String normalizedSport = normalizeText(sport);
        String normalizedHome = normalizeText(home);
        String normalizedAway = normalizeText(away);
        String normalizedDate = startDate.atZone(java.time.ZoneOffset.UTC)
            .format(NORMALIZED_DATE_FORMATTER);
        
        return String.format("%s-%s-%s-%s", 
            normalizedSport, normalizedDate, normalizedHome, normalizedAway);
    }
}
