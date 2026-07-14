package com.singleskickball.manager.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete JSON snapshot returned after a manager action.
 *
 * The browser uses this response to update scoreboards, batting order, current
 * batter, roster rows, and available-player dropdowns without loading another
 * HTML page. Returning a complete snapshot after every mutation also keeps the
 * client resilient: it does not have to guess what changed on the server.
 */
public class ManagerDashboardState {

    private boolean success = true;
    private String message;
    private Long gameWeekId;
    private String gameStatus;
    private boolean gameInProgress;
    private Integer inning;
    private Long currentBattingTeamId;
    private String currentBattingTeamColor;
    private WalkUpSongInfo currentBatter;
    private List<ScoreState> scores = new ArrayList<>();
    private List<TeamState> teams = new ArrayList<>();
    private List<PlayerOption> availablePlayers = new ArrayList<>();

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getGameWeekId() { return gameWeekId; }
    public void setGameWeekId(Long gameWeekId) { this.gameWeekId = gameWeekId; }
    public String getGameStatus() { return gameStatus; }
    public void setGameStatus(String gameStatus) { this.gameStatus = gameStatus; }
    public boolean isGameInProgress() { return gameInProgress; }
    public void setGameInProgress(boolean gameInProgress) { this.gameInProgress = gameInProgress; }
    public Integer getInning() { return inning; }
    public void setInning(Integer inning) { this.inning = inning; }
    public Long getCurrentBattingTeamId() { return currentBattingTeamId; }
    public void setCurrentBattingTeamId(Long currentBattingTeamId) { this.currentBattingTeamId = currentBattingTeamId; }
    public String getCurrentBattingTeamColor() { return currentBattingTeamColor; }
    public void setCurrentBattingTeamColor(String currentBattingTeamColor) { this.currentBattingTeamColor = currentBattingTeamColor; }
    public WalkUpSongInfo getCurrentBatter() { return currentBatter; }
    public void setCurrentBatter(WalkUpSongInfo currentBatter) { this.currentBatter = currentBatter; }
    public List<ScoreState> getScores() { return scores; }
    public void setScores(List<ScoreState> scores) { this.scores = scores; }
    public List<TeamState> getTeams() { return teams; }
    public void setTeams(List<TeamState> teams) { this.teams = teams; }
    public List<PlayerOption> getAvailablePlayers() { return availablePlayers; }
    public void setAvailablePlayers(List<PlayerOption> availablePlayers) { this.availablePlayers = availablePlayers; }

    /** Current score for one team. */
    public static class ScoreState {
        private Long teamId;
        private String color;
        private String name;
        private int runs;

        public ScoreState() { }

        public ScoreState(Long teamId, String color, String name, int runs) {
            this.teamId = teamId;
            this.color = color;
            this.name = name;
            this.runs = runs;
        }

        public Long getTeamId() { return teamId; }
        public void setTeamId(Long teamId) { this.teamId = teamId; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getRuns() { return runs; }
        public void setRuns(int runs) { this.runs = runs; }
    }

    /** Team plus its current weekly batting order. */
    public static class TeamState {
        private Long teamId;
        private String color;
        private String name;
        private String managerName;
        private List<RosterEntryState> roster = new ArrayList<>();

        public Long getTeamId() { return teamId; }
        public void setTeamId(Long teamId) { this.teamId = teamId; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getManagerName() { return managerName; }
        public void setManagerName(String managerName) { this.managerName = managerName; }
        public List<RosterEntryState> getRoster() { return roster; }
        public void setRoster(List<RosterEntryState> roster) { this.roster = roster; }
    }

    /** One player row in a weekly team lineup. */
    public static class RosterEntryState {
        private Long rosterEntryId;
        private Long teamId;
        private Long playerId;
        private String displayName;
        private String fullName;
        private int battingOrder;
        private int runsScored;
        private boolean currentBatter;

        public Long getRosterEntryId() { return rosterEntryId; }
        public void setRosterEntryId(Long rosterEntryId) { this.rosterEntryId = rosterEntryId; }
        public Long getTeamId() { return teamId; }
        public void setTeamId(Long teamId) { this.teamId = teamId; }
        public Long getPlayerId() { return playerId; }
        public void setPlayerId(Long playerId) { this.playerId = playerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public int getBattingOrder() { return battingOrder; }
        public void setBattingOrder(int battingOrder) { this.battingOrder = battingOrder; }
        public int getRunsScored() { return runsScored; }
        public void setRunsScored(int runsScored) { this.runsScored = runsScored; }
        public boolean isCurrentBatter() { return currentBatter; }
        public void setCurrentBatter(boolean currentBatter) { this.currentBatter = currentBatter; }
    }

    /** Registered player option for the add-player dropdown. */
    public static class PlayerOption {
        private Long playerId;
        private String displayName;

        public PlayerOption() { }

        public PlayerOption(Long playerId, String displayName) {
            this.playerId = playerId;
            this.displayName = displayName;
        }

        public Long getPlayerId() { return playerId; }
        public void setPlayerId(Long playerId) { this.playerId = playerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }
}
