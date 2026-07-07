package com.singleskickball.manager.dto;

/**
 * Lightweight DTO returned to the manager dashboard when a batter comes up.
 *
 * The browser uses this object for two jobs:
 * 1. update the visible current batter / batting team indicators without a full
 *    page reload, and
 * 2. play two optional audio files in order: intro clip, then walk-up song.
 */
public class WalkUpSongInfo {

    /** Player-level identity used for intro file naming. */
    private Long playerId;
    private String playerName;

    /** Roster-entry identity used to highlight the current row on the lineup. */
    private Long rosterEntryId;

    /** Current live-game context after the advance. */
    private Long battingTeamId;
    private String battingTeamColor;
    private Integer inning;

    private String artist;
    private String title;

    /** Browser URL to the player's walk-up song MP3, if one exists. */
    private String audioUrl;
    private boolean playable;

    /** Browser URL to the player's intro MP3, if one exists. */
    private String introAudioUrl;
    private boolean introPlayable;

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public Long getRosterEntryId() { return rosterEntryId; }
    public void setRosterEntryId(Long rosterEntryId) { this.rosterEntryId = rosterEntryId; }

    public Long getBattingTeamId() { return battingTeamId; }
    public void setBattingTeamId(Long battingTeamId) { this.battingTeamId = battingTeamId; }

    public String getBattingTeamColor() { return battingTeamColor; }
    public void setBattingTeamColor(String battingTeamColor) { this.battingTeamColor = battingTeamColor; }

    public Integer getInning() { return inning; }
    public void setInning(Integer inning) { this.inning = inning; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }

    public boolean isPlayable() { return playable; }
    public void setPlayable(boolean playable) { this.playable = playable; }

    public String getIntroAudioUrl() { return introAudioUrl; }
    public void setIntroAudioUrl(String introAudioUrl) { this.introAudioUrl = introAudioUrl; }

    public boolean isIntroPlayable() { return introPlayable; }
    public void setIntroPlayable(boolean introPlayable) { this.introPlayable = introPlayable; }
}
