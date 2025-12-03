package com.superodds.domain.model;

/**
 * Canonical outcomes for normalized market options.
 */
public enum OutcomeType {

    // Basic 1X2
    HOME,
    DRAW,
    AWAY,

    // Double chance
    HOME_OR_DRAW,
    DRAW_OR_AWAY,
    HOME_OR_AWAY,

    // Over / Under
    OVER,
    UNDER,

    // BTTS
    YES,
    NO,

    // Combined 1X2 + totals
    HOME_AND_OVER,
    HOME_AND_UNDER,
    DRAW_AND_OVER,
    DRAW_AND_UNDER,
    AWAY_AND_OVER,
    AWAY_AND_UNDER,

    // Combined 1X2 + BTTS
    HOME_AND_YES,
    HOME_AND_NO,
    DRAW_AND_YES,
    DRAW_AND_NO,
    AWAY_AND_YES,
    AWAY_AND_NO,

    // Win + BTTS
    HOME_WIN_AND_YES,
    AWAY_WIN_AND_YES,
    
    // Double chance + totals
    HOME_OR_DRAW_AND_OVER,
    HOME_OR_DRAW_AND_UNDER,
    DRAW_OR_AWAY_AND_OVER,
    DRAW_OR_AWAY_AND_UNDER,
    HOME_OR_AWAY_AND_OVER,
    HOME_OR_AWAY_AND_UNDER,

    // Handicap specific
    HOME_HANDICAP,
    AWAY_HANDICAP,
    HOME_HCP,
    DRAW_HCP,
    AWAY_HCP,

    // Fallback for internal use only (should not be persisted in production).
    OTHER
}
