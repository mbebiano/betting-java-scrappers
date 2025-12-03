package com.superodds.infrastructure.scraper.betmgm;

import com.superodds.domain.model.UnifiedEvent;
import com.superodds.domain.ports.ScraperGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scraper implementation for BetMGM.
 * TODO: Implement full scraping logic based on betmgmraw.py
 */
@Component
public class BetMGMScraper implements ScraperGateway {

    private static final Logger logger = LoggerFactory.getLogger(BetMGMScraper.class);
    private static final String PROVIDER_NAME = "betmgm";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
        logger.info("BetMGM scraper not yet fully implemented");
        // TODO: Implement scraping logic similar to SuperbetScraper
        // Following the pattern from betmgmraw.py
        return new ArrayList<>();
    }
}
