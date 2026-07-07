package com.singleskickball.manager.dto;

/**
 * Lightweight DTO returned to the manager dashboard when a batter comes up.
 *
 * The browser uses this object to play two optional audio files in order:
 *
 * 1. introAudioUrl - short produced intro clip, named by player id
 * 2. audioUrl      - player's walk-up song clip stored on the Player row
 *
 * Keeping both URLs in this DTO lets the browser play the intro first and then
 * automatically continue into the walk-up song without adding another database
 * column while players are already using the production system.
 */
public class WalkUpSongInfo {

    private Long playerId;
    private String playerName;
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
