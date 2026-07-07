package com.singleskickball.manager.dto;

/**
 * Row model used by the manager Walk-Up Songs administration page.
 *
 * This DTO keeps the Thymeleaf template simple. The template should not need to
 * know how files are named on disk, how browser URLs are built, or whether the
 * intro file is found by convention. That logic belongs in the service layer.
 */
public class WalkUpSongAdminRow {

    private Long playerId;
    private String playerName;
    private String displayName;
    private String requestedArtist;
    private String requestedTitle;
    private String requestedSongLabel;

    /** Intro clips are named by convention: /walkup-intros/{playerId}.mp3. */
    private String introFilename;
    private String introUrl;
    private boolean introUploaded;

    /** Walk-up song clips are linked from players.walk_up_song_file_path. */
    private String walkUpSongUrl;
    private String walkUpSongFilename;
    private boolean walkUpSongUploaded;

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRequestedArtist() { return requestedArtist; }
    public void setRequestedArtist(String requestedArtist) { this.requestedArtist = requestedArtist; }

    public String getRequestedTitle() { return requestedTitle; }
    public void setRequestedTitle(String requestedTitle) { this.requestedTitle = requestedTitle; }

    public String getRequestedSongLabel() { return requestedSongLabel; }
    public void setRequestedSongLabel(String requestedSongLabel) { this.requestedSongLabel = requestedSongLabel; }

    public String getIntroFilename() { return introFilename; }
    public void setIntroFilename(String introFilename) { this.introFilename = introFilename; }

    public String getIntroUrl() { return introUrl; }
    public void setIntroUrl(String introUrl) { this.introUrl = introUrl; }

    public boolean isIntroUploaded() { return introUploaded; }
    public void setIntroUploaded(boolean introUploaded) { this.introUploaded = introUploaded; }

    public String getWalkUpSongUrl() { return walkUpSongUrl; }
    public void setWalkUpSongUrl(String walkUpSongUrl) { this.walkUpSongUrl = walkUpSongUrl; }

    public String getWalkUpSongFilename() { return walkUpSongFilename; }
    public void setWalkUpSongFilename(String walkUpSongFilename) { this.walkUpSongFilename = walkUpSongFilename; }

    public boolean isWalkUpSongUploaded() { return walkUpSongUploaded; }
    public void setWalkUpSongUploaded(boolean walkUpSongUploaded) { this.walkUpSongUploaded = walkUpSongUploaded; }
}
