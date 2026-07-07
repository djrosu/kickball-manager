package com.singleskickball.manager.dto;

/**
 * Lightweight DTO returned to the manager screen when a batter comes up.
 *
 * The browser will use this data to decide whether it can play an uploaded MP3
 * or should simply show the artist/title for the manager to play manually from
 * another source.
 */
public class WalkUpSongInfo {

    private Long playerId;
    private String playerName;
    private String artist;
    private String title;
    private String audioUrl;
    private boolean playable;

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
}
