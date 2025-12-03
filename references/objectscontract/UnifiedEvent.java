package com.superodds.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Root domain object for a normalized betting event.
 * All provider-specific JSONs should be mapped into this contract.
 */
public class UnifiedEvent {

    /** Canonical normalized event id (primary key in the domain). */
    private String normalizedId;

    /** Optional legacy / friendly id. */
    private String eventId;

    /** Event metadata such as start time, sport, region, competition. */
    private EventMeta eventMeta;

    /** Home and away participants (names only in this contract). */
    private Participants participants;

    /**
     * Per-source event information, keyed by provider name
     * (e.g. "superbet", "sportingbet", "betmgm").
     */
    private Map<String, SourceSnapshot> sources;

    /**
     * Global flag: true if at least one source has any market with
     * antecipated payment feature enabled.
     */
    private Boolean isPagamentoAntecipado;

    /**
     * Per-source antecipated payment flag:
     * pagamentoAntecipadoPorSource.get("superbet") == true
     * means that superbet offers at least one antecipated payment market
     * for this event.
     */
    private Map<String, Boolean> pagamentoAntecipadoPorSource;

    /**
     * Arbitrary tags per source (for example priceBoostCount).
     */
    private Map<String, SourceTags> tagsBySource;

    /**
     * Normalized markets for this event.
     */
    private List<UnifiedMarket> markets;

    // Getters and setters

    public String getNormalizedId() {
        return normalizedId;
    }

    public void setNormalizedId(String normalizedId) {
        this.normalizedId = normalizedId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public EventMeta getEventMeta() {
        return eventMeta;
    }

    public void setEventMeta(EventMeta eventMeta) {
        this.eventMeta = eventMeta;
    }

    public Participants getParticipants() {
        return participants;
    }

    public void setParticipants(Participants participants) {
        this.participants = participants;
    }

    public Map<String, SourceSnapshot> getSources() {
        return sources;
    }

    public void setSources(Map<String, SourceSnapshot> sources) {
        this.sources = sources;
    }

    public Boolean getIsPagamentoAntecipado() {
        return isPagamentoAntecipado;
    }

    public void setIsPagamentoAntecipado(Boolean isPagamentoAntecipado) {
        this.isPagamentoAntecipado = isPagamentoAntecipado;
    }

    public Map<String, Boolean> getPagamentoAntecipadoPorSource() {
        return pagamentoAntecipadoPorSource;
    }

    public void setPagamentoAntecipadoPorSource(Map<String, Boolean> pagamentoAntecipadoPorSource) {
        this.pagamentoAntecipadoPorSource = pagamentoAntecipadoPorSource;
    }

    public Map<String, SourceTags> getTagsBySource() {
        return tagsBySource;
    }

    public void setTagsBySource(Map<String, SourceTags> tagsBySource) {
        this.tagsBySource = tagsBySource;
    }

    public List<UnifiedMarket> getMarkets() {
        return markets;
    }

    public void setMarkets(List<UnifiedMarket> markets) {
        this.markets = markets;
    }
}
