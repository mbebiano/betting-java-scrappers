package com.superodds.domain.ports;

import com.superodds.domain.model.UnifiedEvent;
import java.util.List;

/**
 * Port for persisting unified events.
 */
public interface EventRepository {
    
    /**
     * Upserts events following the merge rules from the documentation.
     * 
     * @param events List of unified events to upsert
     * @return Number of events inserted or updated
     */
    int upsertEvents(List<UnifiedEvent> events);
    
    /**
     * Finds an existing event by its normalized ID.
     * 
     * @param normalizedId The normalized event ID
     * @return The event if found, null otherwise
     */
    UnifiedEvent findByNormalizedId(String normalizedId);
}
