package com.superodds.domain.model;

/**
 * Arbitrary tags coming from a provider for an event.
 * This can be extended as needed.
 */
public class SourceTags {

    /** Number of boosted prices (if provided by the source). */
    private Integer priceBoostCount;

    public Integer getPriceBoostCount() {
        return priceBoostCount;
    }

    public void setPriceBoostCount(Integer priceBoostCount) {
        this.priceBoostCount = priceBoostCount;
    }
}
