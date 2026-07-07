package com.singleskickball.manager.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary object produced by the roster builder.
 *
 * This is intentionally simple for now. Later we can expose it on the manager
 * dashboard so the manager can see why the auto-roster made certain choices.
 */
public class RosterGenerationSummary {

    private int teamsCreated;
    private int playersAssigned;
    private int mutualPreferencesHonored;
    private final List<String> notes = new ArrayList<>();

    public int getTeamsCreated() { return teamsCreated; }
    public void setTeamsCreated(int teamsCreated) { this.teamsCreated = teamsCreated; }

    public int getPlayersAssigned() { return playersAssigned; }
    public void setPlayersAssigned(int playersAssigned) { this.playersAssigned = playersAssigned; }

    public int getMutualPreferencesHonored() { return mutualPreferencesHonored; }
    public void setMutualPreferencesHonored(int mutualPreferencesHonored) { this.mutualPreferencesHonored = mutualPreferencesHonored; }

    public List<String> getNotes() { return notes; }

    public void addNote(String note) {
        this.notes.add(note);
    }
}
