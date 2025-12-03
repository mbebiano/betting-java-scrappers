package com.superodds.domain.ports;

import com.superodds.domain.model.UnifiedEvent;
import java.util.List;

/**
 * Port for scraping betting data from providers.
 */
public interface ScraperGateway {
    
    /**
     * Gets the name of the provider this scraper handles.
     * 
     * @return Provider name (e.g., "superbet", "sportingbet", "betmgm")
     */
    String getProviderName();
    
    /**
     * Scrapes events from the provider and normalizes them.
     * 
     * @return List of normalized events
     * @throws Exception if scraping fails
     */
    List<UnifiedEvent> scrapeAndNormalize() throws Exception;
}
