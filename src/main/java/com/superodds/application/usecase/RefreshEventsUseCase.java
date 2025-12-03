package com.superodds.application.usecase;

import com.superodds.domain.model.UnifiedEvent;
import com.superodds.domain.ports.EventRepository;
import com.superodds.domain.ports.ScraperGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Use case for refreshing events from all scrapers in parallel.
 */
@Service
public class RefreshEventsUseCase {

    private static final Logger logger = LoggerFactory.getLogger(RefreshEventsUseCase.class);

    private final List<ScraperGateway> scrapers;
    private final EventRepository eventRepository;
    private final ExecutorService executorService;

    public RefreshEventsUseCase(List<ScraperGateway> scrapers, EventRepository eventRepository) {
        this.scrapers = scrapers;
        this.eventRepository = eventRepository;
        // Create thread pool for parallel execution
        // Using fixed thread pool as virtual threads are only available in Java 21+
        this.executorService = Executors.newFixedThreadPool(Math.max(scrapers.size(), 4));
    }

    /**
     * Executes all scrapers in parallel and persists the results.
     * 
     * @return Summary of the refresh operation
     */
    public RefreshSummary execute() {
        logger.info("Starting event refresh with {} scrapers", scrapers.size());
        
        Map<String, Integer> eventsByProvider = new HashMap<>();
        Map<String, String> errorsByProvider = new HashMap<>();
        List<UnifiedEvent> allEvents = new ArrayList<>();
        
        // Execute all scrapers in parallel
        List<CompletableFuture<ScraperResult>> futures = scrapers.stream()
            .map(scraper -> CompletableFuture.supplyAsync(() -> executeScraper(scraper), executorService))
            .toList();
        
        // Wait for all scrapers to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results
        for (CompletableFuture<ScraperResult> future : futures) {
            try {
                ScraperResult result = future.get();
                if (result.events != null) {
                    allEvents.addAll(result.events);
                    eventsByProvider.put(result.providerName, result.events.size());
                    logger.info("Scraper {} returned {} events", result.providerName, result.events.size());
                } else {
                    errorsByProvider.put(result.providerName, result.error);
                    logger.error("Scraper {} failed: {}", result.providerName, result.error);
                }
            } catch (Exception e) {
                logger.error("Error getting scraper result", e);
            }
        }
        
        // Persist all events
        int upsertedCount = 0;
        if (!allEvents.isEmpty()) {
            try {
                logger.info("Upserting {} total events to database", allEvents.size());
                upsertedCount = eventRepository.upsertEvents(allEvents);
                logger.info("Successfully upserted {} events", upsertedCount);
            } catch (Exception e) {
                logger.error("Error upserting events", e);
                errorsByProvider.put("database", e.getMessage());
            }
        }
        
        return new RefreshSummary(eventsByProvider, errorsByProvider, upsertedCount);
    }
    
    private ScraperResult executeScraper(ScraperGateway scraper) {
        String providerName = scraper.getProviderName();
        logger.info("Starting scraper: {}", providerName);
        
        try {
            List<UnifiedEvent> events = scraper.scrapeAndNormalize();
            logger.info("Scraper {} completed successfully with {} events", providerName, events.size());
            return new ScraperResult(providerName, events, null);
        } catch (Exception e) {
            logger.error("Scraper {} failed", providerName, e);
            return new ScraperResult(providerName, null, e.getMessage());
        }
    }
    
    private record ScraperResult(String providerName, List<UnifiedEvent> events, String error) {}
    
    public record RefreshSummary(
        Map<String, Integer> eventsByProvider,
        Map<String, String> errors,
        int totalUpserted
    ) {}
}
