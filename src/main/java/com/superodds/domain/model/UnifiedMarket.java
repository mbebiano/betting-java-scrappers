package com.superodds.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Normalized market for an event (one per logical market).
 */
public class UnifiedMarket {

    /** Canonical market type (resultado_final, btts, etc.). */
    private MarketType marketCanonical;

    /** Period within the match (RegularTime, FirstHalf, etc.). */
    private PeriodType period;

    /** Line (handicap, total, etc.). May be null when not applicable. */
    private BigDecimal line;

    /** What is being counted (GOALS, CARDS, CORNERS, ...). May be null. */
    private HappeningType happening;

    /**
     * When the market is specific to one side, this can indicate
     * HOME or AWAY. For global markets leave it null.
     */
    private ParticipantSide participant;

    /**
     * Time interval inside the period, e.g. "0-3600" (0-60:00 minutes).
     * May be null for full period.
     */
    private String interval;

    /** Last time any option in this market was updated (any provider). */
    private Instant updatedAt;

    /** Normalized options for this market (HOME, DRAW, AWAY, OVER, etc.). */
    private List<UnifiedMarketOption> options;

    public MarketType getMarketCanonical() {
        return marketCanonical;
    }

    public void setMarketCanonical(MarketType marketCanonical) {
        this.marketCanonical = marketCanonical;
    }

    public PeriodType getPeriod() {
        return period;
    }

    public void setPeriod(PeriodType period) {
        this.period = period;
    }

    public BigDecimal getLine() {
        return line;
    }

    public void setLine(BigDecimal line) {
        this.line = line;
    }

    public HappeningType getHappening() {
        return happening;
    }

    public void setHappening(HappeningType happening) {
        this.happening = happening;
    }

    public ParticipantSide getParticipant() {
        return participant;
    }

    public void setParticipant(ParticipantSide participant) {
        this.participant = participant;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<UnifiedMarketOption> getOptions() {
        return options;
    }

    public void setOptions(List<UnifiedMarketOption> options) {
        this.options = options;
    }
}
