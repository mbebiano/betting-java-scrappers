package com.superodds.domain.model;

import java.time.Instant;

/**
 * Per-provider event snapshot information.
 */
public class SourceSnapshot {

    /** Event id in the original provider (e.g. Superbet event id). */
    private String eventSourceId;

    /** First time this event was captured from the provider. */
    private Instant capturedAt;

    /** Last time any data from this provider was updated for this event. */
    private Instant updatedAt;

    public String getEventSourceId() {
        return eventSourceId;
    }

    public void setEventSourceId(String eventSourceId) {
        this.eventSourceId = eventSourceId;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
