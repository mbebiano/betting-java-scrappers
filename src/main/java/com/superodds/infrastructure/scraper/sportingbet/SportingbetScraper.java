package com.superodds.infrastructure.scraper.sportingbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superodds.domain.model.*;
import com.superodds.domain.ports.ScraperGateway;
import com.superodds.infrastructure.persistence.NormalizationUtils;
import com.superodds.infrastructure.scraper.HttpClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Scraper implementation for Sportingbet based on sportingbetraw.py.
 * 
 * Flow:
 * 1) Fetch all football regions/competitions via /bettingoffer/counts
 * 2) For each competition, fetch fixtures via widgetdata (CompetitionLobby)
 * 3) Enrich each fixture with all markets via /bettingoffer/fixture-view
 * 4) Normalize to UnifiedEvent contract
 */
@Component
public class SportingbetScraper implements ScraperGateway {

    private static final Logger logger = LoggerFactory.getLogger(SportingbetScraper.class);
    private static final String PROVIDER_NAME = "sportingbet";
    
    private static final String BASE_URL = "https://www.sportingbet.bet.br";
    private static final String SPORT_URL = BASE_URL + "/pt-br/sports/futebol-4";
    private static final String COUNT_ENDPOINT = BASE_URL + "/cds-api/bettingoffer/counts";
    private static final String WIDGET_ENDPOINT = BASE_URL + "/pt-br/sports/api/widget/widgetdata";
    private static final String FIXTURE_ENDPOINT = BASE_URL + "/cds-api/bettingoffer/fixture-view";
    private static final String DEFAULT_ACCESS_ID = "YTRhMjczYjctNTBlNy00MWZlLTliMGMtMWNkOWQxMThmZTI2";
    private static final int SPORT_ID = 4; // Football
    private static final int MAX_WORKERS = 8;
    
    private static final Map<String, String> COMMON_HEADERS = Map.of(
        "accept", "application/json, text/plain, */*",
        "accept-language", "pt-BR,pt;q=0.9",
        "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "x-device-type", "desktop",
        "x-from-product", "host-app"
    );
    
    private static final Map<String, String> CDS_HEADERS = new HashMap<>(COMMON_HEADERS);
    static {
        CDS_HEADERS.put("x-bwin-cds-api", "https://row8-cds-api.itsfogo.com");
        CDS_HEADERS.put("x-bwin-browser-url", SPORT_URL);
        CDS_HEADERS.put("referer", SPORT_URL);
    }
    
    private static final Map<String, String> SPORTS_HEADERS = new HashMap<>(COMMON_HEADERS);
    static {
        SPORTS_HEADERS.put("x-bwin-sports-api", "prod");
        SPORTS_HEADERS.put("x-bwin-browser-url", SPORT_URL);
        SPORTS_HEADERS.put("referer", SPORT_URL);
    }

    private static final Set<String> TEAM_PARTICIPANT_TYPES = Set.of("HomeTeam", "AwayTeam", "Team", "Competitor");
    private static final Set<String> ALLOWED_MARKET_TYPES = Set.of(
        "3way", "BTTS", "DoubleChance", "DrawNoBet", "Handicap", 
        "2wayHandicap", "ThreeWayAndBTTS", "ToWinAndBTTS", 
        "ThreeWayAndOverUnder", "DoubleChanceAndOverUnder"
    );
    
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<UnifiedEvent> scrapeAndNormalize() throws Exception {
        logger.info("Starting Sportingbet scraper");
        
        try {
            // 1. Fetch competitions
            List<Competition> competitions = fetchCompetitions();
            logger.info("Found {} competitions", competitions.size());
            
            // 2. Fetch fixtures from competitions
            List<JsonNode> allFixtures = new ArrayList<>();
            for (Competition comp : competitions) {
                try {
                    List<JsonNode> fixtures = fetchCompetitionFixtures(comp);
                    allFixtures.addAll(fixtures);
                } catch (Exception e) {
                    logger.warn("Failed to fetch fixtures for competition {}: {}", comp.competitionId, e.getMessage());
                }
            }
            logger.info("Found {} total fixtures", allFixtures.size());
            
            // 3. Deduplicate fixtures
            List<JsonNode> uniqueFixtures = deduplicateFixtures(allFixtures);
            logger.info("After deduplication: {} fixtures", uniqueFixtures.size());
            
            // 4. Enrich fixtures with detailed markets (in parallel)
            List<JsonNode> enrichedFixtures = enrichFixtures(uniqueFixtures);
            logger.info("Enriched {} fixtures with market details", enrichedFixtures.size());
            
            // 5. Normalize to UnifiedEvent
            List<UnifiedEvent> events = new ArrayList<>();
            for (JsonNode fixture : enrichedFixtures) {
                try {
                    UnifiedEvent event = normalizeFixture(fixture);
                    if (event != null && !event.getMarkets().isEmpty()) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize fixture {}: {}", 
                        fixture.has("id") ? fixture.get("id").asText() : "unknown", e.getMessage());
                }
            }
            
            logger.info("Normalized {} events from Sportingbet", events.size());
            return events;
            
        } catch (Exception e) {
            logger.error("Sportingbet scraper failed", e);
            throw e;
        }
    }

    private List<Competition> fetchCompetitions() throws Exception {
        Map<String, String> params = Map.ofEntries(
            Map.entry("x-bwin-accessid", DEFAULT_ACCESS_ID),
            Map.entry("lang", "pt-br"),
            Map.entry("country", "BR"),
            Map.entry("userCountry", "BR"),
            Map.entry("state", "Latest"),
            Map.entry("tagTypes", "Sport,Region,Tournament,Competition,VirtualCompetition,VirtualCompetitionGroup"),
            Map.entry("extendedTags", "Sport,Region,Tournament,Competition,VirtualCompetition,VirtualCompetitionGroup"),
            Map.entry("sportIds", String.valueOf(SPORT_ID)),
            Map.entry("sortBy", "Tags"),
            Map.entry("participantMapping", "All"),
            Map.entry("includeDynamicCategories", "false")
        );
        
        JsonNode response = HttpClientUtil.getJson(COUNT_ENDPOINT, params, CDS_HEADERS);
        
        if (!response.isArray()) {
            logger.warn("Unexpected response type from counts endpoint");
            return List.of();
        }
        
        Map<Integer, String> regions = new HashMap<>();
        List<JsonNode> competitionNodes = new ArrayList<>();
        
        for (JsonNode item : response) {
            JsonNode tag = item.get("tag");
            if (tag == null) continue;
            
            String tagType = tag.has("type") ? tag.get("type").asText() : "";
            
            if ("Region".equals(tagType)) {
                Integer regionId = tag.has("id") ? tag.get("id").asInt() : null;
                if (regionId != null) {
                    String regionName = "";
                    if (tag.has("name") && tag.get("name").has("value")) {
                        regionName = tag.get("name").get("value").asText();
                    }
                    regions.put(regionId, regionName);
                }
            } else if ("Competition".equals(tagType)) {
                competitionNodes.add(tag);
            }
        }
        
        List<Competition> competitions = new ArrayList<>();
        for (JsonNode comp : competitionNodes) {
            Integer compId = comp.has("id") ? comp.get("id").asInt() : null;
            Integer regionId = comp.has("parentId") ? comp.get("parentId").asInt() : null;
            
            if (compId == null || regionId == null) continue;
            
            String compoundId = comp.has("compoundId") ? 
                comp.get("compoundId").asText() : 
                comp.get("sportId").asInt() + ":" + compId;
            
            String competitionName = "";
            if (comp.has("name") && comp.get("name").has("value")) {
                competitionName = comp.get("name").get("value").asText();
            }
            
            competitions.add(new Competition(
                compId,
                compoundId,
                regionId,
                regions.getOrDefault(regionId, ""),
                competitionName
            ));
        }
        
        return competitions;
    }

    private List<JsonNode> fetchCompetitionFixtures(Competition comp) throws Exception {
        Map<String, String> params = Map.ofEntries(
            Map.entry("layoutSize", "Large"),
            Map.entry("page", "CompetitionLobby"),
            Map.entry("sportId", String.valueOf(SPORT_ID)),
            Map.entry("regionId", String.valueOf(comp.regionId)),
            Map.entry("competitionId", String.valueOf(comp.competitionId)),
            Map.entry("compoundCompetitionId", comp.compoundId),
            Map.entry("widgetId", "/mobilesports-v1.0/layout/layout_standards/modules/competition/defaultcontainer"),
            Map.entry("shouldIncludePayload", "true")
        );
        
        JsonNode response = HttpClientUtil.getJson(WIDGET_ENDPOINT, params, SPORTS_HEADERS);
        List<JsonNode> fixtures = extractFixturesFromWidget(response);
        
        // Add metadata to fixtures
        for (JsonNode fixture : fixtures) {
            if (fixture.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).put("_regionId", comp.regionId);
                ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).put("_regionName", comp.regionName);
                ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).put("_competitionId", comp.competitionId);
                ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).put("_competitionName", comp.competitionName);
            }
        }
        
        return fixtures;
    }

    private List<JsonNode> extractFixturesFromWidget(JsonNode node) {
        List<JsonNode> fixtures = new ArrayList<>();
        extractFixturesRecursive(node, fixtures);
        return fixtures;
    }

    private void extractFixturesRecursive(JsonNode node, List<JsonNode> fixtures) {
        if (node == null) return;
        
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if ("fixtures".equals(entry.getKey()) && entry.getValue().isArray()) {
                    for (JsonNode fixture : entry.getValue()) {
                        if (fixture.isObject()) {
                            fixtures.add(fixture);
                        }
                    }
                } else {
                    extractFixturesRecursive(entry.getValue(), fixtures);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                extractFixturesRecursive(item, fixtures);
            }
        }
    }

    private List<JsonNode> deduplicateFixtures(List<JsonNode> fixtures) {
        Map<String, JsonNode> seen = new LinkedHashMap<>();
        for (JsonNode fixture : fixtures) {
            String fixtureId = fixture.has("id") ? fixture.get("id").asText() : 
                              fixture.has("fixtureId") ? fixture.get("fixtureId").asText() : "";
            if (!fixtureId.isEmpty() && !seen.containsKey(fixtureId)) {
                seen.put(fixtureId, fixture);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private List<JsonNode> enrichFixtures(List<JsonNode> fixtures) throws InterruptedException {
        if (fixtures.isEmpty()) return List.of();
        
        List<JsonNode> enriched = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, fixtures.size()));
        
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (JsonNode fixture : fixtures) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String fixtureId = fixture.has("id") ? fixture.get("id").asText() : 
                                         fixture.has("fixtureId") ? fixture.get("fixtureId").asText() : "";
                        if (!fixtureId.isEmpty()) {
                            JsonNode detail = fetchFixtureDetail(fixtureId);
                            if (detail != null) {
                                // Add detail to fixture
                                ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).set("raw", detail);
                                enriched.add(fixture);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to enrich fixture: {}", e.getMessage());
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

    private JsonNode fetchFixtureDetail(String fixtureId) throws Exception {
        Map<String, String> params = Map.ofEntries(
            Map.entry("x-bwin-accessid", DEFAULT_ACCESS_ID),
            Map.entry("lang", "pt-br"),
            Map.entry("country", "BR"),
            Map.entry("userCountry", "BR"),
            Map.entry("offerMapping", "All"),
            Map.entry("scoreboardMode", "Full"),
            Map.entry("fixtureIds", fixtureId),
            Map.entry("state", "Latest"),
            Map.entry("includePrecreatedBetBuilder", "true"),
            Map.entry("supportVirtual", "true"),
            Map.entry("isBettingInsightsEnabled", "false"),
            Map.entry("useRegionalisedConfiguration", "true"),
            Map.entry("includeRelatedFixtures", "false"),
            Map.entry("statisticsModes", "None")
        );
        
        return HttpClientUtil.getJson(FIXTURE_ENDPOINT, params, CDS_HEADERS);
    }

    private UnifiedEvent normalizeFixture(JsonNode enrichedFixture) {
        JsonNode raw = enrichedFixture.has("raw") ? enrichedFixture.get("raw") : enrichedFixture;
        JsonNode fixture = raw.has("fixture") ? raw.get("fixture") : raw;
        
        if (!fixture.isObject()) return null;
        
        // Validate fixture
        if (!isValidFixture(fixture)) return null;
        
        // Extract participants
        List<JsonNode> participants = new ArrayList<>();
        if (fixture.has("participants") && fixture.get("participants").isArray()) {
            for (JsonNode p : fixture.get("participants")) {
                participants.add(p);
            }
        }
        
        if (participants.size() < 2) return null;
        
        String home = null;
        String away = null;
        for (JsonNode p : participants) {
            JsonNode props = p.has("properties") ? p.get("properties") : null;
            if (props == null) continue;
            
            String type = props.has("type") ? props.get("type").asText() : "";
            String name = p.has("name") ? p.get("name").asText() : "";
            
            if ("HomeTeam".equals(type) || "1".equals(type)) {
                home = name;
            } else if ("AwayTeam".equals(type) || "2".equals(type)) {
                away = name;
            }
        }
        
        if (home == null || away == null) {
            // Fallback: use first two participants if available
            if (participants.size() >= 2) {
                home = participants.get(0).has("name") ? participants.get(0).get("name").asText() : "Home";
                away = participants.get(1).has("name") ? participants.get(1).get("name").asText() : "Away";
            } else {
                // Cannot determine participants - skip this fixture
                logger.warn("Cannot determine home/away participants for fixture");
                return null;
            }
        }
        
        // Extract basic info
        String sport = "Futebol";
        String region = enrichedFixture.has("_regionName") ? enrichedFixture.get("_regionName").asText() : "Unknown";
        String competition = enrichedFixture.has("_competitionName") ? enrichedFixture.get("_competitionName").asText() : "Unknown";
        
        String startDateStr = fixture.has("startDate") ? fixture.get("startDate").asText() : null;
        String cutOffDateStr = fixture.has("cutOffDate") ? fixture.get("cutOffDate").asText() : startDateStr;
        
        Instant startDate = Instant.now();
        Instant cutOffDate = Instant.now();
        
        if (startDateStr != null) {
            try {
                // Handle various ISO formats
                String normalized = startDateStr.replace(" ", "T");
                if (!normalized.endsWith("Z") && !normalized.contains("+")) {
                    normalized += "Z";
                }
                startDate = Instant.parse(normalized);
            } catch (Exception e) {
                logger.warn("Failed to parse startDate '{}', using current time", startDateStr);
            }
        }
        
        if (cutOffDateStr != null) {
            try {
                String normalized = cutOffDateStr.replace(" ", "T");
                if (!normalized.endsWith("Z") && !normalized.contains("+")) {
                    normalized += "Z";
                }
                cutOffDate = Instant.parse(normalized);
            } catch (Exception e) {
                logger.warn("Failed to parse cutOffDate '{}', using startDate", cutOffDateStr);
                cutOffDate = startDate;
            }
        } else {
            cutOffDate = startDate;
        }
        
        // Create event metadata
        EventMeta eventMeta = new EventMeta();
        eventMeta.setStartDate(startDate);
        eventMeta.setCutOffDate(cutOffDate);
        eventMeta.setSport(sport);
        eventMeta.setRegion(region);
        eventMeta.setCompetition(competition);
        
        // Create participants
        Participants parts = new Participants();
        parts.setHome(home);
        parts.setAway(away);
        
        // Generate normalized ID
        String normalizedId = NormalizationUtils.generateNormalizedId(sport, startDate, home, away);
        
        // Create source snapshot
        String fixtureId = fixture.has("id") ? fixture.get("id").asText() : 
                         enrichedFixture.has("id") ? enrichedFixture.get("id").asText() : "";
        
        SourceSnapshot sourceSnapshot = new SourceSnapshot();
        sourceSnapshot.setEventSourceId(fixtureId);
        sourceSnapshot.setCapturedAt(Instant.now());
        sourceSnapshot.setUpdatedAt(Instant.now());
        
        // Process markets
        List<UnifiedMarket> markets = new ArrayList<>();
        Map<String, Integer> tagCounts = new HashMap<>();
        boolean hasPagamentoAntecipado = false;
        
        if (fixture.has("optionMarkets") && fixture.get("optionMarkets").isArray()) {
            for (JsonNode market : fixture.get("optionMarkets")) {
                try {
                    UnifiedMarket unifiedMarket = normalizeMarket(market, home, away);
                    if (unifiedMarket != null && !unifiedMarket.getOptions().isEmpty()) {
                        markets.add(unifiedMarket);
                        
                        // Check for price boost
                        for (UnifiedMarketOption option : unifiedMarket.getOptions()) {
                            if (option.getSources().containsKey(PROVIDER_NAME)) {
                                OptionSourceData sourceData = option.getSources().get(PROVIDER_NAME);
                                if (sourceData.getMeta() != null && 
                                    sourceData.getMeta().containsKey("priceBoost") && 
                                    "true".equals(sourceData.getMeta().get("priceBoost"))) {
                                    tagCounts.merge("priceBoostCount", 1, Integer::sum);
                                }
                                if (Boolean.TRUE.equals(sourceData.getPagamentoAntecipado())) {
                                    hasPagamentoAntecipado = true;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize market: {}", e.getMessage());
                }
            }
        }
        
        if (markets.isEmpty()) return null;
        
        // Create unified event
        UnifiedEvent event = new UnifiedEvent();
        event.setNormalizedId(normalizedId);
        event.setEventId(normalizedId);
        event.setEventMeta(eventMeta);
        event.setParticipants(parts);
        event.setSources(Map.of(PROVIDER_NAME, sourceSnapshot));
        event.setMarkets(markets);
        event.setIsPagamentoAntecipado(hasPagamentoAntecipado);
        event.setPagamentoAntecipadoPorSource(Map.of(PROVIDER_NAME, hasPagamentoAntecipado));
        
        if (!tagCounts.isEmpty()) {
            SourceTags tags = new SourceTags();
            tags.setPriceBoostCount(tagCounts.get("priceBoostCount"));
            event.setTagsBySource(Map.of(PROVIDER_NAME, tags));
        }
        
        return event;
    }

    private boolean isValidFixture(JsonNode fixture) {
        // Check sport
        Integer sportId = null;
        if (fixture.has("sport") && fixture.get("sport").has("id")) {
            sportId = fixture.get("sport").get("id").asInt();
        } else if (fixture.has("scoreboard") && fixture.get("scoreboard").has("sportId")) {
            sportId = fixture.get("scoreboard").get("sportId").asInt();
        }
        
        if (sportId != null && sportId != SPORT_ID) {
            return false;
        }
        
        // Check has markets
        if (!fixture.has("optionMarkets") || !fixture.get("optionMarkets").isArray() || 
            fixture.get("optionMarkets").size() == 0) {
            return false;
        }
        
        // Check fixture type
        String fixtureType = fixture.has("fixtureType") ? fixture.get("fixtureType").asText() : "";
        if (!fixtureType.isEmpty() && !"PairGame".equalsIgnoreCase(fixtureType)) {
            return false;
        }
        
        return true;
    }

    private UnifiedMarket normalizeMarket(JsonNode market, String home, String away) {
        Map<String, String> parameters = extractParameters(market);
        String marketType = parameters.getOrDefault("MarketType", "");
        String period = parameters.getOrDefault("Period", "RegularTime");
        
        // Filter by allowed market types
        if (!ALLOWED_MARKET_TYPES.contains(marketType)) {
            return null;
        }
        
        // Filter by period - only RegularTime for now
        if (!"RegularTime".equals(period)) {
            return null;
        }
        
        // Map to canonical market
        String marketName = market.has("name") ? 
            (market.get("name").isObject() && market.get("name").has("value") ? 
                market.get("name").get("value").asText() : 
                market.get("name").asText()) : 
            "";
        
        SportingbetMarketMapper.MarketMapping mapping = 
            SportingbetMarketMapper.mapMarket(parameters, marketName);
        
        if (mapping == null || mapping.marketType() == null) {
            return null; // Discard unmapped markets (Rule 9)
        }
        
        // Create unified market
        UnifiedMarket unifiedMarket = new UnifiedMarket();
        unifiedMarket.setMarketCanonical(mapping.marketType());
        unifiedMarket.setPeriod(mapping.period());
        unifiedMarket.setLine(mapping.line());
        unifiedMarket.setHappening(mapping.happening());
        unifiedMarket.setParticipant(mapping.participant());
        unifiedMarket.setInterval(mapping.interval());
        unifiedMarket.setUpdatedAt(Instant.now());
        
        // Process options
        List<UnifiedMarketOption> options = new ArrayList<>();
        if (market.has("options") && market.get("options").isArray()) {
            for (JsonNode option : market.get("options")) {
                try {
                    UnifiedMarketOption unifiedOption = normalizeOption(option, marketType, parameters, home, away);
                    if (unifiedOption != null) {
                        options.add(unifiedOption);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to normalize option: {}", e.getMessage());
                }
            }
        }
        
        unifiedMarket.setOptions(options);
        return unifiedMarket;
    }

    private Map<String, String> extractParameters(JsonNode market) {
        Map<String, String> params = new HashMap<>();
        if (market.has("parameters") && market.get("parameters").isArray()) {
            for (JsonNode param : market.get("parameters")) {
                String key = param.has("key") ? param.get("key").asText() : "";
                String value = param.has("value") ? param.get("value").asText() : "";
                if (!key.isEmpty()) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    private UnifiedMarketOption normalizeOption(JsonNode option, String marketType, 
                                                Map<String, String> parameters, 
                                                String home, String away) {
        String code = option.has("code") ? option.get("code").asText() : "";
        String optionName = option.has("name") ? 
            (option.get("name").isObject() && option.get("name").has("value") ? 
                option.get("name").get("value").asText() : 
                option.get("name").asText()) : 
            "";
        
        OutcomeType outcome = SportingbetMarketMapper.mapOutcome(code, marketType, parameters);
        if (outcome == null) {
            return null;
        }
        
        // Get price
        JsonNode priceNode = option.has("price") ? option.get("price") : null;
        BigDecimal decimalPrice = null;
        if (priceNode != null && priceNode.has("decimal")) {
            decimalPrice = new BigDecimal(priceNode.get("decimal").asText());
        }
        
        if (decimalPrice == null) {
            return null; // Skip options without price
        }
        
        Price price = new Price();
        price.setDecimal(decimalPrice);
        
        // Create option source data
        OptionSourceData sourceData = new OptionSourceData();
        sourceData.setPagamentoAntecipado(false); // Default to false
        sourceData.setCapturedAt(Instant.now());
        sourceData.setUpdatedAt(Instant.now());
        sourceData.setStatusRaw(option.has("status") ? option.get("status").asText() : "");
        sourceData.setMarketId(option.has("marketId") ? option.get("marketId").asText() : "");
        sourceData.setOptionId(option.has("id") ? option.get("id").asText() : "");
        sourceData.setPrice(price);
        
        // Check for boosted price or special tags
        Map<String, Object> meta = new HashMap<>();
        if (option.has("boostedPrice") && !option.get("boostedPrice").isNull()) {
            meta.put("priceBoost", "true");
            meta.put("boostedPrice", option.get("boostedPrice").asText());
        }
        
        if (!meta.isEmpty()) {
            sourceData.setMeta(meta);
        }
        
        // Create unified option
        UnifiedMarketOption unifiedOption = new UnifiedMarketOption();
        unifiedOption.setOutcome(outcome);
        unifiedOption.setLabel(optionName);
        unifiedOption.setSources(Map.of(PROVIDER_NAME, sourceData));
        
        return unifiedOption;
    }

    private record Competition(
        int competitionId,
        String compoundId,
        int regionId,
        String regionName,
        String competitionName
    ) {}
}
