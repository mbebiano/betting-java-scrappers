package com.superodds.infrastructure.scraper.sportingbet;

import com.superodds.domain.model.UnifiedEvent;
import com.superodds.domain.ports.ScraperGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scraper implementation for Sportingbet.
 * TODO: Implement full scraping logic based on sportingbetraw.py
 */
@Component
public class SportingbetScraper implements ScraperGateway {

    private static final Logger logger = LoggerFactory.getLogger(SportingbetScraper.class);
    private static final String PROVIDER_NAME = "sportingbet";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
        logger.info("Sportingbet scraper not yet fully implemented");
        // TODO: Implement scraping logic similar to SuperbetScraper
        // Following the pattern from sportingbetraw.py
        return new ArrayList<>();
    }
}
