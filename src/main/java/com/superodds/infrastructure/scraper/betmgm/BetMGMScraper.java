package com.superodds.infrastructure.scraper.betmgm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superodds.domain.model.*;
import com.superodds.domain.ports.ScraperGateway;
import com.superodds.infrastructure.persistence.NormalizationUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Scraper implementation for BetMGM (Kambi platform) based on betmgmraw.py.
 * 
 * Flow:
 * 1) Use GraphQL AllLeaguesPaginatedQuery to list football events
 * 2) For each eventId, fetch detailed markets via Kambi offering-api
 * 3) Normalize to UnifiedEvent contract
 */
@Component
public class BetMGMScraper implements ScraperGateway {

    private static final Logger logger = LoggerFactory.getLogger(BetMGMScraper.class);
    private static final String PROVIDER_NAME = "betmgm";
    
    private static final String GRAPHQL_URL = "https://www.betmgm.bet.br/api/lmbas";
    private static final String OFFERING_EVENT_URL_TEMPLATE = 
        "https://us1.offering-api.kambicdn.com/offering/v2018/betmgmbr/betoffer/event/%s.json";
    
    private static final int DEFAULT_DAYS = 4;
    private static final int DEFAULT_FIRST = 50;
    private static final int MAX_WORKERS = 8;
    
    private static final String PERSISTED_QUERY_HASH = 
        "b858aece8798aeb4f1d93bfd29d95ac3dc0932f609a1710dd2d55bd5988eb954";
    
    private static final Map<String, String> GRAPHQL_HEADERS = Map.of(
        "content-type", "application/json",
        "x-app-id", "sportsbook",
        "x-client-id", "sportsbook",
        "x-app-version", "3.57.0",
        "x-client-version", "3.57.0",
        "x-kambi-env", "PROD"
    );
    
    private static final Map<String, String> OFFERING_HEADERS = Map.of(
        "accept", "*/*",
        "origin", "https://www.betmgm.bet.br",
        "referer", "https://www.betmgm.bet.br/",
        "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    );
    
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
        logger.info("Starting BetMGM scraper");
        
        try {
            // 1. Fetch events via GraphQL
            List<JsonNode> events = fetchEventsGraphQL();
            logger.info("Found {} events from BetMGM GraphQL", events.size());
            
            // 2. Deduplicate events
            List<JsonNode> uniqueEvents = deduplicateEvents(events);
            logger.info("After deduplication: {} events", uniqueEvents.size());
            
            // 3. Enrich events with detailed markets (in parallel)
            List<JsonNode> enrichedEvents = enrichEvents(uniqueEvents);
            logger.info("Enriched {} events with market details", enrichedEvents.size());
            
            // 4. Normalize to UnifiedEvent
            List<UnifiedEvent> normalized = new ArrayList<>();
            for (JsonNode event : enrichedEvents) {
                try {
                    UnifiedEvent unifiedEvent = normalizeEvent(event);
                    if (unifiedEvent != null && !unifiedEvent.getMarkets().isEmpty()) {
                        normalized.add(unifiedEvent);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize event {}: {}", 
                        event.has("id") ? event.get("id").asText() : "unknown", e.getMessage());
                }
            }
            
            logger.info("Normalized {} events from BetMGM", normalized.size());
            return normalized;
            
        } catch (Exception e) {
            logger.error("BetMGM scraper failed", e);
            throw e;
        }
    }

    private List<JsonNode> fetchEventsGraphQL() throws Exception {
        List<JsonNode> allEvents = new ArrayList<>();
        String after = "0";
        int page = 0;
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            while (true) {
                ObjectNode payload = createGraphQLPayload(after, DEFAULT_FIRST, DEFAULT_DAYS);
                
                HttpPost post = new HttpPost(GRAPHQL_URL);
                for (Map.Entry<String, String> header : GRAPHQL_HEADERS.entrySet()) {
                    post.setHeader(header.getKey(), header.getValue());
                }
                post.setEntity(new StringEntity(mapper.writeValueAsString(payload)));
                
                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    HttpEntity entity = response.getEntity();
                    String responseBody = EntityUtils.toString(entity);
                    JsonNode data = mapper.readTree(responseBody);
                    
                    JsonNode edges = data.path("data").path("viewer").path("sports")
                        .path("sportsEventsConnection").path("edges");
                    
                    JsonNode pageInfo = data.path("data").path("viewer").path("sports")
                        .path("sportsEventsConnection").path("pageInfo");
                    
                    page++;
                    logger.info("GraphQL page {}: {} edges", page, edges.size());
                    
                    for (JsonNode edge : edges) {
                        JsonNode node = edge.get("node");
                        if (node != null && node.has("groups")) {
                            for (JsonNode group : node.get("groups")) {
                                if (group.has("events")) {
                                    for (JsonNode event : group.get("events")) {
                                        allEvents.add(event);
                                    }
                                }
                            }
                        }
                    }
                    
                    boolean hasNext = pageInfo.has("hasNextPage") && pageInfo.get("hasNextPage").asBoolean();
                    if (!hasNext || !pageInfo.has("endCursor")) {
                        break;
                    }
                    
                    after = pageInfo.get("endCursor").asText();
                }
            }
        }
        
        return allEvents;
    }

    private ObjectNode createGraphQLPayload(String after, int first, int days) {
        ObjectNode variables = mapper.createObjectNode();
        variables.put("after", after);
        variables.put("first", first);
        variables.put("skipAllLeaguesSportsQuery", false);
        
        ObjectNode filter = mapper.createObjectNode();
        filter.put("eventType", "MATCH");
        filter.put("sport", "football");
        filter.put("upcomingDays", days);
        variables.set("filter", filter);
        
        variables.putArray("grouping").add("COUNTRY_AZ").add("LEAGUE_POPULARITY");
        variables.put("lang", "pt_BR");
        variables.put("market", "BR");
        variables.put("offering", "betmgmbr");
        
        ObjectNode popularEventsGroup = mapper.createObjectNode();
        popularEventsGroup.put("country", "brazil");
        popularEventsGroup.put("league", "brasileirao_serie_a");
        popularEventsGroup.put("sport", "football");
        variables.putArray("popularEventsGroup").add(popularEventsGroup);
        
        ObjectNode persistedQuery = mapper.createObjectNode();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", PERSISTED_QUERY_HASH);
        
        ObjectNode extensions = mapper.createObjectNode();
        extensions.set("persistedQuery", persistedQuery);
        
        ObjectNode payload = mapper.createObjectNode();
        payload.put("operationName", "AllLeaguesPaginatedQuery");
        payload.set("variables", variables);
        payload.set("extensions", extensions);
        
        return payload;
    }

    private List<JsonNode> deduplicateEvents(List<JsonNode> events) {
        Map<String, JsonNode> seen = new LinkedHashMap<>();
        for (JsonNode event : events) {
            String eventId = event.has("id") ? event.get("id").asText() :
                           event.has("eventId") ? event.get("eventId").asText() : "";
            if (!eventId.isEmpty() && !seen.containsKey(eventId)) {
                seen.put(eventId, event);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private List<JsonNode> enrichEvents(List<JsonNode> events) throws InterruptedException {
        if (events.isEmpty()) return List.of();
        
        List<JsonNode> enriched = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, events.size()));
        
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (JsonNode event : events) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String eventId = event.has("id") ? event.get("id").asText() :
                                       event.has("eventId") ? event.get("eventId").asText() : "";
                        if (!eventId.isEmpty()) {
                            JsonNode detail = fetchEventDetail(eventId);
                            if (detail != null) {
                                ((ObjectNode) event).set("raw", detail);
                                enriched.add(event);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to enrich event: {}", e.getMessage());
                    }
                }, executor);
                
                futures.add(future);
            }
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
        } finally {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        
        return new ArrayList<>(enriched);
    }

    private JsonNode fetchEventDetail(String eventId) throws Exception {
        String url = String.format(OFFERING_EVENT_URL_TEMPLATE, eventId);
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url + 
                "?channel_id=1&client_id=200&includeParticipants=true&lang=pt_BR&market=BR&range_size=1");
            
            for (Map.Entry<String, String> header : OFFERING_HEADERS.entrySet()) {
                get.setHeader(header.getKey(), header.getValue());
            }
            
            try (CloseableHttpResponse response = httpClient.execute(get)) {
                if (response.getCode() != 200) {
                    logger.warn("Event detail {} returned status {}", eventId, response.getCode());
                    return null;
                }
                
                HttpEntity entity = response.getEntity();
                String responseBody = EntityUtils.toString(entity);
                return mapper.readTree(responseBody);
            }
        }
    }

    private UnifiedEvent normalizeEvent(JsonNode enrichedEvent) {
        JsonNode raw = enrichedEvent.has("raw") ? enrichedEvent.get("raw") : enrichedEvent;
        JsonNode event = raw.has("events") && raw.get("events").isArray() && raw.get("events").size() > 0 ?
                        raw.get("events").get(0) : raw.has("event") ? raw.get("event") : raw;
        
        if (!event.isObject()) return null;
        
        // Extract basic info
        String eventName = enrichedEvent.has("name") ? enrichedEvent.get("name").asText() :
                          enrichedEvent.has("englishName") ? enrichedEvent.get("englishName").asText() : "";
        
        // Parse participants from event name (e.g., "Team A v Team B")
        String[] parts = eventName.split(" v | vs | x ", 2);
        String home = parts.length > 0 ? parts[0].trim() : "Home";
        String away = parts.length > 1 ? parts[1].trim() : "Away";
        
        // Try to get from participants if available
        if (event.has("participants") && event.get("participants").isArray()) {
            List<JsonNode> participants = new ArrayList<>();
            for (JsonNode p : event.get("participants")) {
                participants.add(p);
            }
            if (participants.size() >= 2) {
                home = participants.get(0).has("name") ? participants.get(0).get("name").asText() : home;
                away = participants.get(1).has("name") ? participants.get(1).get("name").asText() : away;
            }
        }
        
        String sport = "Futebol";
        String region = "Unknown";
        String competition = "Unknown";
        
        // Try to extract from GraphQL data
        if (enrichedEvent.has("league") && enrichedEvent.get("league").has("name")) {
            competition = enrichedEvent.get("league").get("name").asText();
        }
        if (enrichedEvent.has("league") && enrichedEvent.get("league").has("country") && 
            enrichedEvent.get("league").get("country").has("name")) {
            region = enrichedEvent.get("league").get("country").get("name").asText();
        }
        
        // Get start time
        String startStr = enrichedEvent.has("start") ? enrichedEvent.get("start").asText() :
                         event.has("start") ? event.get("start").asText() : null;
        Instant startDate = startStr != null ? Instant.parse(startStr) : Instant.now();
        
        // Create event metadata
        EventMeta eventMeta = new EventMeta();
        eventMeta.setStartDate(startDate);
        eventMeta.setCutOffDate(startDate);
        eventMeta.setSport(sport);
        eventMeta.setRegion(region);
        eventMeta.setCompetition(competition);
        
        // Create participants
        Participants participants = new Participants();
        participants.setHome(home);
        participants.setAway(away);
        
        // Generate normalized ID
        String normalizedId = NormalizationUtils.generateNormalizedId(sport, startDate, home, away);
        
        // Create source snapshot
        String eventId = event.has("id") ? event.get("id").asText() :
                        enrichedEvent.has("id") ? enrichedEvent.get("id").asText() : "";
        
        SourceSnapshot sourceSnapshot = new SourceSnapshot();
        sourceSnapshot.setEventSourceId(eventId);
        sourceSnapshot.setCapturedAt(Instant.now());
        sourceSnapshot.setUpdatedAt(Instant.now());
        
        // Process markets
        List<UnifiedMarket> markets = new ArrayList<>();
        
        if (raw.has("betOffers") && raw.get("betOffers").isArray()) {
            for (JsonNode betOffer : raw.get("betOffers")) {
                try {
                    UnifiedMarket market = normalizeMarket(betOffer, home, away);
                    if (market != null && !market.getOptions().isEmpty()) {
                        markets.add(market);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize market: {}", e.getMessage());
                }
            }
        }
        
        if (markets.isEmpty()) return null;
        
        // Create unified event
        UnifiedEvent unifiedEvent = new UnifiedEvent();
        unifiedEvent.setNormalizedId(normalizedId);
        unifiedEvent.setEventId(normalizedId);
        unifiedEvent.setEventMeta(eventMeta);
        unifiedEvent.setParticipants(participants);
        unifiedEvent.setSources(Map.of(PROVIDER_NAME, sourceSnapshot));
        unifiedEvent.setMarkets(markets);
        unifiedEvent.setIsPagamentoAntecipado(false);
        unifiedEvent.setPagamentoAntecipadoPorSource(Map.of(PROVIDER_NAME, false));
        
        return unifiedEvent;
    }

    private UnifiedMarket normalizeMarket(JsonNode betOffer, String home, String away) {
        JsonNode criterion = betOffer.has("criterion") ? betOffer.get("criterion") : null;
        if (criterion == null) return null;
        
        String criterionLabel = criterion.has("label") ? criterion.get("label").asText() : "";
        String betOfferType = betOffer.has("betOfferType") && betOffer.get("betOfferType").has("name") ?
                             betOffer.get("betOfferType").get("name").asText() : "";
        
        String label = betOffer.has("label") ? betOffer.get("label").asText() : "";
        
        // Map to canonical market
        BetMGMMarketMapper.MarketMapping mapping = 
            BetMGMMarketMapper.mapMarket(criterionLabel, label, betOfferType);
        
        if (mapping == null || mapping.marketType() == null) {
            return null; // Discard unmapped markets (Rule 9)
        }
        
        // Create unified market
        UnifiedMarket market = new UnifiedMarket();
        market.setMarketCanonical(mapping.marketType());
        market.setPeriod(mapping.period());
        market.setLine(mapping.line());
        market.setHappening(mapping.happening());
        market.setParticipant(mapping.participant());
        market.setInterval(mapping.interval());
        market.setUpdatedAt(Instant.now());
        
        // Process outcomes
        List<UnifiedMarketOption> options = new ArrayList<>();
        if (betOffer.has("outcomes") && betOffer.get("outcomes").isArray()) {
            for (JsonNode outcome : betOffer.get("outcomes")) {
                try {
                    UnifiedMarketOption option = normalizeOutcome(outcome, criterionLabel, mapping.marketType());
                    if (option != null) {
                        options.add(option);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize outcome: {}", e.getMessage());
                }
            }
        }
        
        market.setOptions(options);
        return market;
    }

    private UnifiedMarketOption normalizeOutcome(JsonNode outcome, String criterionLabel, MarketType marketType) {
        String outcomeLabel = outcome.has("label") ? outcome.get("label").asText() : "";
        
        OutcomeType outcomeType = BetMGMMarketMapper.mapOutcome(outcomeLabel, criterionLabel, marketType);
        if (outcomeType == null) {
            return null;
        }
        
        // Get odds
        BigDecimal oddsDecimal = null;
        String oddsFractional = null;
        String oddsAmerican = null;
        
        if (outcome.has("odds")) {
            JsonNode odds = outcome.get("odds");
            if (odds.has("decimal")) {
                oddsDecimal = new BigDecimal(odds.get("decimal").asText());
            }
            if (odds.has("fractional")) {
                oddsFractional = odds.get("fractional").asText();
            }
            if (odds.has("american")) {
                oddsAmerican = odds.get("american").asText();
            }
        }
        
        // Try oddsDecimal directly
        if (oddsDecimal == null && outcome.has("oddsDecimal")) {
            oddsDecimal = new BigDecimal(outcome.get("oddsDecimal").asText());
        }
        
        if (oddsDecimal == null) {
            return null; // Skip options without odds
        }
        
        Price price = new Price();
        price.setDecimal(oddsDecimal);
        if (oddsFractional != null) {
            price.setFractional(oddsFractional);
        }
        if (oddsAmerican != null) {
            price.setAmerican(oddsAmerican);
        }
        
        // Create option source data
        OptionSourceData sourceData = new OptionSourceData();
        sourceData.setPagamentoAntecipado(false);
        sourceData.setCapturedAt(Instant.now());
        sourceData.setUpdatedAt(Instant.now());
        sourceData.setStatusRaw(outcome.has("status") ? outcome.get("status").asText() : "");
        sourceData.setMarketId(outcome.has("betOfferId") ? outcome.get("betOfferId").asText() : "");
        sourceData.setOptionId(outcome.has("id") ? outcome.get("id").asText() : "");
        sourceData.setPrice(price);
        
        // Create unified option
        UnifiedMarketOption option = new UnifiedMarketOption();
        option.setOutcome(outcomeType);
        option.setLabel(outcomeLabel);
        option.setSources(Map.of(PROVIDER_NAME, sourceData));
        
        return option;
    }
}
