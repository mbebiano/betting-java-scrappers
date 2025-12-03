package com.superodds.contract;

import java.time.Instant;
import java.util.Map;

/**
 * Provider-specific information for a normalized option.
 */
public class OptionSourceData {

    /** Whether this option has antecipated payment on this provider. */
    private Boolean pagamentoAntecipado;

    /** First time this option from this provider was captured. */
    private Instant capturedAt;

    /** Last update time for this option from this provider. */
    private Instant updatedAt;

    /** Raw status string as sent by the provider (e.g. OPEN, Visible, active). */
    private String statusRaw;

    /** Provider-specific market id. */
    private String marketId;

    /** Provider-specific option id. */
    private String optionId;

    /** Prices for this option on this provider. */
    private Price price;

    /**
     * Arbitrary meta data from the provider
     * (tags, criterion, lifetime, etc.).
     */
    private Map<String, Object> meta;

    public Boolean getPagamentoAntecipado() {
        return pagamentoAntecipado;
    }

    public void setPagamentoAntecipado(Boolean pagamentoAntecipado) {
        this.pagamentoAntecipado = pagamentoAntecipado;
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

    public String getStatusRaw() {
        return statusRaw;
    }

    public void setStatusRaw(String statusRaw) {
        this.statusRaw = statusRaw;
    }

    public String getMarketId() {
        return marketId;
    }

    public void setMarketId(String marketId) {
        this.marketId = marketId;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public Price getPrice() {
        return price;
    }

    public void setPrice(Price price) {
        this.price = price;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }
}
