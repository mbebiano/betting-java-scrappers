package com.superodds.contract;

/**
 * Participants for an event. For this contract we only keep normalized names.
 */
public class Participants {

    /** Home team name. */
    private String home;

    /** Away team name. */
    private String away;

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getAway() {
        return away;
    }

    public void setAway(String away) {
        this.away = away;
    }
}
