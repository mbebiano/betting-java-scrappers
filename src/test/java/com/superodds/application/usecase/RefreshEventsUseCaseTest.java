package com.superodds.application.usecase;

import com.superodds.domain.model.*;
import com.superodds.domain.ports.EventRepository;
import com.superodds.domain.ports.ScraperGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RefreshEventsUseCase.
 */
class RefreshEventsUseCaseTest {

    private RefreshEventsUseCase useCase;
    private TestEventRepository repository;
    private List<ScraperGateway> scrapers;

    @BeforeEach
    void setUp() {
        repository = new TestEventRepository();
        scrapers = new ArrayList<>();
        
        // Add test scrapers
        scrapers.add(new TestScraper("test1", 2));
        scrapers.add(new TestScraper("test2", 3));
        
        useCase = new RefreshEventsUseCase(scrapers, repository);
    }

    @Test
    void testExecuteSuccess() {
        RefreshEventsUseCase.RefreshSummary summary = useCase.execute();
        
        assertNotNull(summary);
        assertEquals(2, summary.eventsByProvider().get("test1"));
        assertEquals(3, summary.eventsByProvider().get("test2"));
        assertEquals(5, summary.totalUpserted());
        assertTrue(summary.errors().isEmpty());
    }

    @Test
    void testExecuteWithErrors() {
        // Add a failing scraper
        scrapers.add(new FailingScraper("failing"));
        useCase = new RefreshEventsUseCase(scrapers, repository);
        
        RefreshEventsUseCase.RefreshSummary summary = useCase.execute();
        
        assertNotNull(summary);
        assertEquals(2, summary.eventsByProvider().get("test1"));
        assertEquals(3, summary.eventsByProvider().get("test2"));
        assertTrue(summary.errors().containsKey("failing"));
    }

    /**
     * Test scraper that creates dummy events.
     */
    private static class TestScraper implements ScraperGateway {
        private final String name;
        private final int eventCount;

        public TestScraper(String name, int eventCount) {
            this.name = name;
            this.eventCount = eventCount;
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public List<UnifiedEvent> scrapeAndNormalize() {
            List<UnifiedEvent> events = new ArrayList<>();
            
            for (int i = 0; i < eventCount; i++) {
                UnifiedEvent event = new UnifiedEvent();
                event.setNormalizedId("TEST-" + name + "-" + i);
                event.setEventId("event-" + i);
                
                EventMeta meta = new EventMeta();
                meta.setStartDate(Instant.now());
                meta.setSport("Futebol");
                event.setEventMeta(meta);
                
                Participants participants = new Participants();
                participants.setHome("Team A");
                participants.setAway("Team B");
                event.setParticipants(participants);
                
                // Add a simple market
                UnifiedMarket market = new UnifiedMarket();
                market.setMarketCanonical(MarketType.RESULTADO_FINAL);
                market.setPeriod(PeriodType.REGULAR_TIME);
                market.setUpdatedAt(Instant.now());
                
                // Add options
                UnifiedMarketOption option = new UnifiedMarketOption();
                option.setOutcome(OutcomeType.HOME);
                option.setLabel("Team A");
                
                OptionSourceData sourceData = new OptionSourceData();
                sourceData.setMarketId("1");
                sourceData.setOptionId("1");
                
                Price price = new Price();
                price.setDecimal(BigDecimal.valueOf(2.5));
                sourceData.setPrice(price);
                
                Map<String, OptionSourceData> sources = new HashMap<>();
                sources.put(name, sourceData);
                option.setSources(sources);
                
                market.setOptions(List.of(option));
                event.setMarkets(List.of(market));
                
                events.add(event);
            }
            
            return events;
        }
    }

    /**
     * Test scraper that always fails.
     */
    private static class FailingScraper implements ScraperGateway {
        private final String name;

        public FailingScraper(String name) {
            this.name = name;
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
            throw new Exception("Test failure");
        }
    }

    /**
     * Test repository that stores events in memory.
     */
    private static class TestEventRepository implements EventRepository {
        private final Map<String, UnifiedEvent> events = new HashMap<>();

        @Override
        public int upsertEvents(List<UnifiedEvent> eventList) {
            int count = 0;
            for (UnifiedEvent event : eventList) {
                String id = event.getNormalizedId() != null ? 
                    event.getNormalizedId() : event.getEventId();
                if (id != null) {
                    events.put(id, event);
                    count++;
                }
            }
            return count;
        }

        @Override
        public UnifiedEvent findByNormalizedId(String normalizedId) {
            return events.get(normalizedId);
        }
    }
}
