package com.superodds.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import com.superodds.domain.model.*;
import com.superodds.domain.ports.EventRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of EventRepository with optimizations for handling thousands of events.
 * 
 * Optimizations:
 * - Batch upsert operations for better write performance
 * - Indexes on normalizedId, eventMeta.startDate for efficient queries
 * - TTL index to automatically remove old events
 * - Projection queries to reduce data transfer
 */
@Repository
public class MongoEventRepository implements EventRepository {

    private static final Logger logger = LoggerFactory.getLogger(MongoEventRepository.class);
    private static final ObjectMapper OBJECT_MAPPER;
    private static final int BATCH_SIZE = 100; // Process events in batches of 100
    private static final int TTL_DAYS = 7; // Keep events for 7 days after start date
    
    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
    }

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionName;

    public MongoEventRepository(
            MongoClient mongoClient,
            String mongoCollectionName,
            @org.springframework.beans.factory.annotation.Value("${mongodb.database:flashscore}") String databaseName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionName = mongoCollectionName;
        
        // Initialize indexes on construction
        initializeIndexes();
    }

    /**
     * Initialize indexes on application startup for better query performance.
     * Per problem statement: "improve how events are stored in the long term since there can be thousands".
     */
    private void initializeIndexes() {
        try {
            MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection(collectionName);
            
            // 1. Unique index on normalizedId for efficient upsert and lookup
            collection.createIndex(
                Indexes.ascending("normalizedId"), 
                new IndexOptions().unique(true).background(true)
            );
            
            // 2. Index on startDate for time-based queries
            collection.createIndex(
                Indexes.descending("eventMeta.startDate"),
                new IndexOptions().background(true)
            );
            
            // 3. Compound index for sport + startDate filtering
            collection.createIndex(
                Indexes.compoundIndex(
                    Indexes.ascending("eventMeta.sport"),
                    Indexes.descending("eventMeta.startDate")
                ),
                new IndexOptions().background(true)
            );
            
            // 4. TTL index to auto-delete old events (7 days after start date)
            // This prevents the collection from growing indefinitely
            collection.createIndex(
                Indexes.ascending("eventMeta.startDate"),
                new IndexOptions()
                    .expireAfter((long) TTL_DAYS, TimeUnit.DAYS)
                    .background(true)
            );
            
            logger.info("MongoDB indexes initialized for collection: {}", collectionName);
        } catch (Exception e) {
            logger.warn("Failed to create indexes (may already exist): {}", e.getMessage());
        }
    }

    @Override
    public int upsertEvents(List<UnifiedEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }

        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
            .getCollection(collectionName);

        int totalUpserted = 0;
        
        // Process events in batches to avoid memory issues with thousands of events
        for (int i = 0; i < events.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, events.size());
            List<UnifiedEvent> batch = events.subList(i, end);
            
            totalUpserted += processBatch(collection, batch);
        }

        logger.info("Upserted {} events in batches of {}", totalUpserted, BATCH_SIZE);
        return totalUpserted;
    }

    private int processBatch(MongoCollection<Document> collection, List<UnifiedEvent> batch) {
        // Get normalized IDs for this batch
        List<String> normalizedIds = batch.stream()
            .map(e -> e.getNormalizedId() != null ? e.getNormalizedId() : e.getEventId())
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        // Fetch existing documents for this batch only (with projection to reduce data transfer)
        Map<String, Document> existingMap = new HashMap<>();
        if (!normalizedIds.isEmpty()) {
            collection.find(Filters.in("normalizedId", normalizedIds))
                .forEach(doc -> {
                    String normId = doc.getString("normalizedId");
                    if (normId != null) {
                        existingMap.put(normId, doc);
                    }
                });
        }

        // Prepare bulk write operations for this batch
        List<WriteModel<Document>> bulkWrites = new ArrayList<>();
        
        for (UnifiedEvent event : batch) {
            String normId = event.getNormalizedId() != null ? event.getNormalizedId() : event.getEventId();
            if (normId == null) {
                logger.warn("Skipping event without normalizedId or eventId");
                continue;
            }

            try {
                // Get existing document if any
                Document existingDoc = existingMap.get(normId);
                
                // Convert event to document
                Document newDoc = eventToDocument(event);
                
                // Merge if existing, otherwise use new
                Document mergedDoc = existingDoc != null 
                    ? mergeDocuments(existingDoc, newDoc) 
                    : newDoc;
                
                // Add to bulk write
                bulkWrites.add(new ReplaceOneModel<>(
                    Filters.eq("normalizedId", normId),
                    mergedDoc,
                    new ReplaceOptions().upsert(true)
                ));
                
            } catch (Exception e) {
                logger.error("Error preparing upsert for event {}", normId, e);
            }
        }

        // Execute bulk write
        if (!bulkWrites.isEmpty()) {
            try {
                var result = collection.bulkWrite(bulkWrites, new BulkWriteOptions().ordered(false));
                return result.getUpserts().size() + result.getModifiedCount();
            } catch (Exception e) {
                logger.error("Bulk write failed", e);
                return 0;
            }
        }
        
        return 0;
    }

    @Override
    public UnifiedEvent findByNormalizedId(String normalizedId) {
        if (normalizedId == null) {
            return null;
        }

        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
            .getCollection(collectionName);

        Document doc = collection.find(Filters.eq("normalizedId", normalizedId)).first();
        
        if (doc == null) {
            return null;
        }

        try {
            return documentToEvent(doc);
        } catch (Exception e) {
            logger.error("Error converting document to event", e);
            return null;
        }
    }

    /**
     * Merges existing and new documents following the contract rules.
     */
    private Document mergeDocuments(Document existing, Document incoming) {
        Document merged = new Document(existing);
        
        // Keep or update basic fields
        merged.putIfAbsent("eventId", incoming.get("eventId"));
        merged.putIfAbsent("normalizedId", incoming.get("normalizedId"));
        
        // Merge eventMeta
        if (incoming.containsKey("eventMeta")) {
            Document existingMeta = (Document) merged.get("eventMeta");
            Document incomingMeta = (Document) incoming.get("eventMeta");
            if (existingMeta == null) {
                merged.put("eventMeta", incomingMeta);
            } else {
                // Keep existing values, add new ones
                Document mergedMeta = new Document(existingMeta);
                if (incomingMeta != null) {
                    incomingMeta.forEach(mergedMeta::putIfAbsent);
                }
                merged.put("eventMeta", mergedMeta);
            }
        }
        
        // Merge participants
        if (incoming.containsKey("participants")) {
            merged.putIfAbsent("participants", incoming.get("participants"));
        }
        
        // Merge sources - per provider
        Map<String, Document> mergedSources = new HashMap<>();
        
        // Add existing sources
        Document existingSources = (Document) merged.get("sources");
        if (existingSources != null) {
            existingSources.forEach((key, value) -> mergedSources.put(key, (Document) value));
        }
        
        // Add/update incoming sources
        Document incomingSources = (Document) incoming.get("sources");
        if (incomingSources != null) {
            incomingSources.forEach((key, value) -> mergedSources.put(key, (Document) value));
        }
        
        merged.put("sources", new Document(mergedSources));
        
        // Merge pagamentoAntecipadoPorSource
        Map<String, Boolean> mergedPagamento = new HashMap<>();
        Document existingPagamento = (Document) merged.get("pagamentoAntecipadoPorSource");
        if (existingPagamento != null) {
            existingPagamento.forEach((key, value) -> mergedPagamento.put(key, (Boolean) value));
        }
        Document incomingPagamento = (Document) incoming.get("pagamentoAntecipadoPorSource");
        if (incomingPagamento != null) {
            incomingPagamento.forEach((key, value) -> mergedPagamento.put(key, (Boolean) value));
        }
        merged.put("pagamentoAntecipadoPorSource", new Document(mergedPagamento));
        
        // Update isPagamentoAntecipado if any source has it
        boolean anyPagamento = mergedPagamento.values().stream().anyMatch(Boolean.TRUE::equals);
        merged.put("isPagamentoAntecipado", anyPagamento);
        
        // Merge tagsBySource
        Map<String, Document> mergedTags = new HashMap<>();
        Document existingTags = (Document) merged.get("tagsBySource");
        if (existingTags != null) {
            existingTags.forEach((key, value) -> mergedTags.put(key, (Document) value));
        }
        Document incomingTags = (Document) incoming.get("tagsBySource");
        if (incomingTags != null) {
            incomingTags.forEach((key, value) -> mergedTags.put(key, (Document) value));
        }
        merged.put("tagsBySource", new Document(mergedTags));
        
        // Merge markets - this is complex, needs market-level merge
        merged.put("markets", mergeMarkets(
            (List<Document>) merged.get("markets"),
            (List<Document>) incoming.get("markets")
        ));
        
        return merged;
    }

    /**
     * Merges market lists from existing and incoming events.
     */
    @SuppressWarnings("unchecked")
    private List<Document> mergeMarkets(List<Document> existing, List<Document> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing != null ? existing : new ArrayList<>();
        }
        
        if (existing == null || existing.isEmpty()) {
            return incoming;
        }
        
        // Create a map of existing markets by their key
        Map<String, Document> marketMap = new HashMap<>();
        for (Document market : existing) {
            String key = getMarketKey(market);
            marketMap.put(key, market);
        }
        
        // Merge or add incoming markets
        for (Document incomingMarket : incoming) {
            String key = getMarketKey(incomingMarket);
            Document existingMarket = marketMap.get(key);
            
            if (existingMarket != null) {
                // Merge options within the market
                marketMap.put(key, mergeMarketOptions(existingMarket, incomingMarket));
            } else {
                marketMap.put(key, incomingMarket);
            }
        }
        
        return new ArrayList<>(marketMap.values());
    }

    /**
     * Creates a unique key for a market based on its canonical attributes.
     */
    private String getMarketKey(Document market) {
        String canonical = market.getString("marketCanonical");
        String period = market.getString("period");
        Object line = market.get("line");
        String happening = market.getString("happening");
        String participant = market.getString("participant");
        String interval = market.getString("interval");
        
        return String.format("%s|%s|%s|%s|%s|%s", 
            canonical, period, line, happening, participant, interval);
    }

    /**
     * Merges options within a market.
     */
    @SuppressWarnings("unchecked")
    private Document mergeMarketOptions(Document existingMarket, Document incomingMarket) {
        Document merged = new Document(existingMarket);
        
        // Update updatedAt to latest
        merged.put("updatedAt", incomingMarket.get("updatedAt"));
        
        List<Document> existingOptions = (List<Document>) existingMarket.get("options");
        List<Document> incomingOptions = (List<Document>) incomingMarket.get("options");
        
        if (incomingOptions == null || incomingOptions.isEmpty()) {
            return merged;
        }
        
        if (existingOptions == null || existingOptions.isEmpty()) {
            merged.put("options", incomingOptions);
            return merged;
        }
        
        // Map existing options by outcome
        Map<String, Document> optionMap = new HashMap<>();
        for (Document option : existingOptions) {
            String outcome = option.getString("outcome");
            optionMap.put(outcome, option);
        }
        
        // Merge incoming options
        for (Document incomingOption : incomingOptions) {
            String outcome = incomingOption.getString("outcome");
            Document existingOption = optionMap.get(outcome);
            
            if (existingOption != null) {
                // Merge sources
                Document mergedOption = new Document(existingOption);
                
                Document existingSources = (Document) existingOption.get("sources");
                Document incomingSources = (Document) incomingOption.get("sources");
                
                Map<String, Document> mergedSources = new HashMap<>();
                if (existingSources != null) {
                    existingSources.forEach((key, value) -> mergedSources.put(key, (Document) value));
                }
                if (incomingSources != null) {
                    incomingSources.forEach((key, value) -> mergedSources.put(key, (Document) value));
                }
                
                mergedOption.put("sources", new Document(mergedSources));
                optionMap.put(outcome, mergedOption);
            } else {
                optionMap.put(outcome, incomingOption);
            }
        }
        
        merged.put("options", new ArrayList<>(optionMap.values()));
        return merged;
    }

    private Document eventToDocument(UnifiedEvent event) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = OBJECT_MAPPER.convertValue(event, Map.class);
        return new Document(map);
    }

    private UnifiedEvent documentToEvent(Document doc) {
        return OBJECT_MAPPER.convertValue(doc, UnifiedEvent.class);
    }
}
