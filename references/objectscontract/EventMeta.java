package com.superodds.contract;

import java.time.Instant;

/**
 * Metadata for an event: kickoff times and classification.
 */
public class EventMeta {

    /** Event kickoff in UTC, ISO-8601 encoded externally. */
    private Instant startDate;

    /** Cutoff time for pre-live betting in UTC (optional). */
    private Instant cutOffDate;

    /** Sport name, e.g. "Futebol". */
    private String sport;

    /** Region / country name, e.g. "Brasil". */
    private String region;

    /** Competition / league name, e.g. "Brasileiro A". */
    private String competition;

    public Instant getStartDate() {
        return startDate;
    }

    public void setStartDate(Instant startDate) {
        this.startDate = startDate;
    }

    public Instant getCutOffDate() {
        return cutOffDate;
    }

    public void setCutOffDate(Instant cutOffDate) {
        this.cutOffDate = cutOffDate;
    }

    public String getSport() {
        return sport;
    }

    public void setSport(String sport) {
        this.sport = sport;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCompetition() {
        return competition;
    }

    public void setCompetition(String competition) {
        this.competition = competition;
    }
}
