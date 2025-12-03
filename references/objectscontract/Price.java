package com.superodds.contract;

import java.math.BigDecimal;

/**
 * Price representation for an option on a specific provider.
 */
public class Price {

    /**
     * Decimal odds (required).
     * Example: 2.87, 1.9500.
     */
    private BigDecimal decimal;

    /**
     * Fractional odds (optional).
     * Example: "9/2".
     */
    private String fractional;

    /**
     * American odds (optional).
     * Example: "450".
     */
    private String american;

    public BigDecimal getDecimal() {
        return decimal;
    }

    public void setDecimal(BigDecimal decimal) {
        this.decimal = decimal;
    }

    public String getFractional() {
        return fractional;
    }

    public void setFractional(String fractional) {
        this.fractional = fractional;
    }

    public String getAmerican() {
        return american;
    }

    public void setAmerican(String american) {
        this.american = american;
    }
}
