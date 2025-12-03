package com.superodds.domain.model;

/**
 * Canonical market types supported by the unified contract.
 * Any market not mapped to one of these must be discarded by the normalizer.
 */
public enum MarketType {
    RESULTADO_FINAL("resultado_final"),
    DUPLA_CHANCE("dupla_chance"),
    BTTS("btts"),
    DRAW_NO_BET("draw_no_bet"),
    RESULTADO_TOTAL_GOLS("resultado_total_gols"),
    HANDICAP_ASIAN_2WAY("handicap_asian_2way"),
    RESULTADO_BTTS("resultado_btts"),
    RESULTADO_BTTS_WIN("resultado_btts_win"),
    HANDICAP_3WAY("handicap_3way"),
    DUPLA_CHANCE_TOTAL_GOLS("dupla_chance_total_gols"),
    TOTAL_CARTOES_OVER_UNDER("total_cartoes_over_under"),
    TOTAL_ESCANTEIOS_OVER_UNDER("total_escanteios_over_under"),
    TOTAL_GOLS_OVER_UNDER("total_gols_over_under");

    private final String canonicalKey;

    MarketType(String canonicalKey) {
        this.canonicalKey = canonicalKey;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    @Override
    public String toString() {
        return canonicalKey;
    }
}
