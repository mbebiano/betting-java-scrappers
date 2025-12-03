package com.superodds.infrastructure.rest;

import com.superodds.application.usecase.RefreshEventsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for event operations.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private static final Logger logger = LoggerFactory.getLogger(EventController.class);

    private final RefreshEventsUseCase refreshEventsUseCase;

    public EventController(RefreshEventsUseCase refreshEventsUseCase) {
        this.refreshEventsUseCase = refreshEventsUseCase;
    }

    /**
     * Endpoint to trigger refresh of all events from all scrapers.
     * 
     * POST /events/refresh
     * 
     * @return Summary of the refresh operation
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshEventsUseCase.RefreshSummary> refreshEvents() {
        logger.info("Received request to refresh events");
        
        try {
            RefreshEventsUseCase.RefreshSummary summary = refreshEventsUseCase.execute();
            logger.info("Event refresh completed. Total upserted: {}", summary.totalUpserted());
            
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            logger.error("Error refreshing events", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
