package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * Connects a player to a weekly team. Batting order and runs are stored here
 * because they are different for every week/game.
 */
@Entity
@Table(name = "team_roster_entries",
       uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "player_id"}))
public class TeamRosterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int battingOrder;
    private int runsScored;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
    public int getBattingOrder() { return battingOrder; }
    public void setBattingOrder(int battingOrder) { this.battingOrder = battingOrder; }
    public int getRunsScored() { return runsScored; }
    public void setRunsScored(int runsScored) { this.runsScored = runsScored; }
}
