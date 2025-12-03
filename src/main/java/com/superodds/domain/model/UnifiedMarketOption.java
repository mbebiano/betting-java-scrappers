package com.superodds.domain.model;

import java.util.Map;

/**
 * Normalized option inside a market (e.g. HOME, DRAW, AWAY, OVER, UNDER, ...).
 * Prices and meta-information are stored per source.
 */
public class UnifiedMarketOption {

    /**
     * Canonical outcome type for this option.
     */
    private OutcomeType outcome;

    /**
     * Human-readable label, e.g. "Grêmio", "X", "Mais de 2,5".
     */
    private String label;

    /**
     * Per-source data (prices, status, ids, meta) keyed by provider name.
     */
    private Map<String, OptionSourceData> sources;

    public OutcomeType getOutcome() {
        return outcome;
    }

    public void setOutcome(OutcomeType outcome) {
        this.outcome = outcome;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Map<String, OptionSourceData> getSources() {
        return sources;
    }

    public void setSources(Map<String, OptionSourceData> sources) {
        this.sources = sources;
    }
}
