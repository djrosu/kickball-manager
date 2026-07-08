package com.singleskickball.manager.dto;

/**
 * Lightweight DTO returned to the manager dashboard when a batter comes up.
 *
 * This object intentionally carries two kinds of information:
 *
 * 1. Audio metadata
 *    - intro audio URL
 *    - walk-up song URL
 *    - player/song display text
 *
 * 2. Live-game identity metadata
 *    - rosterEntryId
 *    - battingTeamId
 *    - battingTeamColor
 *    - inning
 *
 * The identity fields are important because the manager dashboard advances the
 * batter asynchronously. After the AJAX call returns, JavaScript needs the exact
 * roster-entry id to move the highlighted "At Bat" badge without reloading the
 * whole page.
 */
public class WalkUpSongInfo {

    /** TeamRosterEntry id for the current batter. This is the most precise UI key. */
    private Long rosterEntryId;

    /** Team id for the batting team. Used as a fallback UI key with playerId. */
    private Long battingTeamId;

    /** Team color/name displayed in the Current At-Bat card. */
    private String battingTeamColor;

    /** Current inning displayed in the Current At-Bat card. */
    private Integer inning;

    /** Player id for the current batter. Used by intro file convention and UI fallback. */
    private Long playerId;

    /** Display name, usually nickname when available. */
    private String playerName;

    /** Player-entered walk-up song artist. */
    private String artist;

    /** Player-entered walk-up song title. */
    private String title;

    /** Browser URL to the player's walk-up song MP3, if one exists. */
    private String audioUrl;
    private boolean playable;

    /** Browser URL to the player's intro MP3, if one exists. */
    private String introAudioUrl;
    private boolean introPlayable;

    public Long getRosterEntryId() { return rosterEntryId; }
    public void setRosterEntryId(Long rosterEntryId) { this.rosterEntryId = rosterEntryId; }

    public Long getBattingTeamId() { return battingTeamId; }
    public void setBattingTeamId(Long battingTeamId) { this.battingTeamId = battingTeamId; }

    public String getBattingTeamColor() { return battingTeamColor; }
    public void setBattingTeamColor(String battingTeamColor) { this.battingTeamColor = battingTeamColor; }

    public Integer getInning() { return inning; }
    public void setInning(Integer inning) { this.inning = inning; }

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

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
