package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * Tracks the live state of a game: current batting team, current batter, and inning.
 */
@Entity
@Table(name = "game_state")
public class GameState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "game_week_id")
    private GameWeek gameWeek;

    @ManyToOne
    @JoinColumn(name = "current_batting_team_id")
    private Team currentBattingTeam;

    @ManyToOne
    @JoinColumn(name = "current_batter_roster_entry_id")
    private TeamRosterEntry currentBatterRosterEntry;

    private int inning = 1;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public GameWeek getGameWeek() { return gameWeek; }
    public void setGameWeek(GameWeek gameWeek) { this.gameWeek = gameWeek; }
    public Team getCurrentBattingTeam() { return currentBattingTeam; }
    public void setCurrentBattingTeam(Team currentBattingTeam) { this.currentBattingTeam = currentBattingTeam; }
    public TeamRosterEntry getCurrentBatterRosterEntry() { return currentBatterRosterEntry; }
    public void setCurrentBatterRosterEntry(TeamRosterEntry currentBatterRosterEntry) { this.currentBatterRosterEntry = currentBatterRosterEntry; }
    public int getInning() { return inning; }
    public void setInning(int inning) { this.inning = inning; }
}
