package com.singleskickball.manager.dto;

/**
 * Current score for a team during the live game.
 */
public class TeamScore {

    private Long teamId;
    private String color;
    private String name;
    private int runs;

    public TeamScore(Long teamId, String color, String name, int runs) {
        this.teamId = teamId;
        this.color = color;
        this.name = name;
        this.runs = runs;
    }

    public Long getTeamId() { return teamId; }
    public String getColor() { return color; }
    public String getName() { return name; }
    public int getRuns() { return runs; }
}
