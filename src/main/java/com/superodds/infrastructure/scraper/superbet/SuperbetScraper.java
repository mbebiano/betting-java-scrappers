package com.superodds.infrastructure.scraper.superbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.superodds.domain.model.*;
import com.superodds.domain.ports.ScraperGateway;
import com.superodds.infrastructure.persistence.NormalizationUtils;
import com.superodds.infrastructure.scraper.HttpClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scraper implementation for Superbet.
 */
@Component
public class SuperbetScraper implements ScraperGateway {

    private static final Logger logger = LoggerFactory.getLogger(SuperbetScraper.class);
    
    private static final String PROVIDER_NAME = "superbet";
    private static final String BASE_URL = "https://superbet.com/api/widget";
    private static final int SPORT_ID = 5; // Football
    private static final int DAYS = 3;
    
    // Market IDs that we want to scrape
    private static final List<Integer> MARKET_IDS = List.of(
        547,  // Resultado Final (1X2)
        539,  // Ambas as Equipes Marcam
        531,  // Dupla Chance
        555,  // Empate Anula Aposta
        546,  // Handicap 3-way
        530,  // Handicap Asiático
        532,  // Resultado Final & Ambas Marcam
        542,  // Dupla Chance & Total de Gols
        557   // Resultado Final & Total de Gols
    );
    
    private static final Map<String, String> HEADERS = Map.of(
        "accept", "application/json, text/plain, */*",
        "accept-language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    );

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
        logger.info("Starting Superbet scraper");
        
        // Get list of events
        List<JsonNode> rawEvents = fetchEvents();
        logger.info("Fetched {} events from Superbet", rawEvents.size());
        
        // Normalize each event
        List<UnifiedEvent> normalizedEvents = new ArrayList<>();
        for (JsonNode rawEvent : rawEvents) {
            try {
                UnifiedEvent event = normalizeEvent(rawEvent);
                if (event != null) {
                    normalizedEvents.add(event);
                }
            } catch (Exception e) {
                logger.error("Error normalizing event", e);
            }
        }
        
        logger.info("Normalized {} events from Superbet", normalizedEvents.size());
        return normalizedEvents;
    }

    private List<JsonNode> fetchEvents() throws Exception {
        List<JsonNode> allEvents = new ArrayList<>();
        
        // Calculate date range
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime endDate = now.plusDays(DAYS);
        
        String startDateStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String endDateStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        // Fetch events list
        String url = BASE_URL + "/v2/pt-BR/sports/" + SPORT_ID + "/events";
        Map<String, String> params = Map.of(
            "from", startDateStr,
            "to", endDateStr
        );
        
        JsonNode response = HttpClientUtil.getJson(url, params, HEADERS);
        
        if (response.has("data") && response.get("data").isArray()) {
            JsonNode events = response.get("data");
            
            // For each event, fetch full details
            for (JsonNode event : events) {
                String eventId = event.get("eventId").asText();
                try {
                    JsonNode fullEvent = fetchEventDetails(eventId);
                    if (fullEvent != null) {
                        allEvents.add(fullEvent);
                    }
                } catch (Exception e) {
                    logger.error("Error fetching event details for {}", eventId, e);
                }
            }
        }
        
        return allEvents;
    }

    private JsonNode fetchEventDetails(String eventId) throws Exception {
        String url = BASE_URL + "/v2/pt-BR/events/" + eventId;
        String marketIds = String.join(",", MARKET_IDS.stream().map(String::valueOf).toList());
        
        Map<String, String> params = Map.of(
            "includeMarkets", marketIds
        );
        
        JsonNode response = HttpClientUtil.getJson(url, params, HEADERS);
        
        if (response.has("data") && response.get("data").isArray() && !response.get("data").isEmpty()) {
            return response.get("data").get(0);
        }
        
        return null;
    }

    private UnifiedEvent normalizeEvent(JsonNode rawEvent) {
        UnifiedEvent event = new UnifiedEvent();
        Instant now = Instant.now();
        
        // Extract basic info
        String eventId = rawEvent.get("eventId").asText();
        String matchName = rawEvent.get("matchName").asText();
        
        // Split match name into home and away
        String[] parts = matchName.split("·");
        String home = parts.length > 0 ? parts[0].trim() : "";
        String away = parts.length > 1 ? parts[1].trim() : "";
        
        // Parse match date
        String matchDateStr = rawEvent.get("matchDate").asText();
        Instant startDate = parseMatchDate(matchDateStr);
        
        // Set participants
        Participants participants = new Participants();
        participants.setHome(home);
        participants.setAway(away);
        event.setParticipants(participants);
        
        // Set event meta
        EventMeta meta = new EventMeta();
        meta.setStartDate(startDate);
        meta.setSport("Futebol");
        meta.setRegion("Brasil"); // Could be extracted from tournament info
        meta.setCompetition(""); // Could be extracted from tournament info
        event.setEventMeta(meta);
        
        // Generate normalized ID
        String normalizedId = NormalizationUtils.generateNormalizedId(
            "Futebol", startDate, home, away
        );
        event.setNormalizedId(normalizedId);
        event.setEventId(eventId);
        
        // Set source info
        SourceSnapshot sourceSnapshot = new SourceSnapshot();
        sourceSnapshot.setEventSourceId(eventId);
        sourceSnapshot.setCapturedAt(now);
        sourceSnapshot.setUpdatedAt(now);
        
        Map<String, SourceSnapshot> sources = new HashMap<>();
        sources.put(PROVIDER_NAME, sourceSnapshot);
        event.setSources(sources);
        
        // Parse markets and options
        List<UnifiedMarket> markets = parseMarkets(rawEvent, now);
        event.setMarkets(markets);
        
        // Calculate pagamento antecipado flags
        boolean hasPagamentoAntecipado = markets.stream()
            .flatMap(m -> m.getOptions().stream())
            .flatMap(o -> o.getSources().values().stream())
            .anyMatch(s -> Boolean.TRUE.equals(s.getPagamentoAntecipado()));
        
        event.setIsPagamentoAntecipado(hasPagamentoAntecipado);
        
        Map<String, Boolean> pagamentoBySource = new HashMap<>();
        pagamentoBySource.put(PROVIDER_NAME, hasPagamentoAntecipado);
        event.setPagamentoAntecipadoPorSource(pagamentoBySource);
        
        // Tags (count price boosts if available)
        String matchTags = rawEvent.has("matchTags") ? rawEvent.get("matchTags").asText() : "";
        int priceBoostCount = matchTags.contains("price_boost") ? 1 : 0;
        
        SourceTags tags = new SourceTags();
        tags.setPriceBoostCount(priceBoostCount);
        
        Map<String, SourceTags> tagsBySource = new HashMap<>();
        tagsBySource.put(PROVIDER_NAME, tags);
        event.setTagsBySource(tagsBySource);
        
        return event;
    }

    private Instant parseMatchDate(String matchDateStr) {
        try {
            // Format: "2025-11-29 21:00:00"
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime ldt = LocalDateTime.parse(matchDateStr, formatter);
            return ldt.atZone(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            logger.error("Error parsing date: {}", matchDateStr, e);
            return Instant.now();
        }
    }

    private List<UnifiedMarket> parseMarkets(JsonNode rawEvent, Instant now) {
        Map<String, UnifiedMarket> marketMap = new HashMap<>();
        
        if (!rawEvent.has("odds")) {
            return new ArrayList<>();
        }
        
        JsonNode oddsArray = rawEvent.get("odds");
        
        for (JsonNode odd : oddsArray) {
            try {
                int marketId = odd.get("marketId").asInt();
                String marketName = odd.get("marketName").asText();
                
                // Map to canonical market type
                SuperbetMarketMapper.MarketMapping mapping = SuperbetMarketMapper.mapMarket(marketId, marketName);
                
                if (mapping == null || mapping.marketType() == null) {
                    // Skip unmapped markets
                    continue;
                }
                
                // Create market key
                String marketKey = SuperbetMarketMapper.getMarketKey(mapping);
                
                UnifiedMarket market = marketMap.computeIfAbsent(marketKey, k -> {
                    UnifiedMarket m = new UnifiedMarket();
                    m.setMarketCanonical(mapping.marketType());
                    m.setPeriod(mapping.period());
                    m.setLine(mapping.line());
                    m.setHappening(mapping.happening());
                    m.setParticipant(mapping.participant());
                    m.setInterval(mapping.interval());
                    m.setUpdatedAt(now);
                    m.setOptions(new ArrayList<>());
                    return m;
                });
                
                // Parse option
                String optionName = odd.get("name").asText();
                BigDecimal price = BigDecimal.valueOf(odd.get("price").asDouble());
                String status = odd.get("status").asText();
                String outcomeId = odd.get("outcomeId").asText();
                
                // Map to canonical outcome
                OutcomeType outcome = SuperbetMarketMapper.mapOutcome(marketId, marketName, optionName);
                
                if (outcome == null || outcome == OutcomeType.OTHER) {
                    // Skip unmapped outcomes
                    continue;
                }
                
                // Find or create option
                UnifiedMarketOption option = market.getOptions().stream()
                    .filter(o -> o.getOutcome() == outcome)
                    .findFirst()
                    .orElseGet(() -> {
                        UnifiedMarketOption newOption = new UnifiedMarketOption();
                        newOption.setOutcome(outcome);
                        newOption.setLabel(optionName);
                        newOption.setSources(new HashMap<>());
                        market.getOptions().add(newOption);
                        return newOption;
                    });
                
                // Create source data
                OptionSourceData sourceData = new OptionSourceData();
                sourceData.setMarketId(String.valueOf(marketId));
                sourceData.setOptionId(outcomeId);
                sourceData.setStatusRaw(status);
                sourceData.setCapturedAt(now);
                sourceData.setUpdatedAt(now);
                sourceData.setPagamentoAntecipado(false); // TODO: detect from tags
                
                Price priceObj = new Price();
                priceObj.setDecimal(price);
                sourceData.setPrice(priceObj);
                
                option.getSources().put(PROVIDER_NAME, sourceData);
                
            } catch (Exception e) {
                logger.error("Error parsing odd", e);
            }
        }
        
        return new ArrayList<>(marketMap.values());
    }
}
