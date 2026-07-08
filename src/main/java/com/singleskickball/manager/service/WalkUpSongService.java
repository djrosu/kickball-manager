package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides walk-up audio metadata for the manager game screen.
 *
 * This service is intentionally the single place that knows how to translate a
 * Player/TeamRosterEntry/GameState into browser-ready audio data. Keeping this
 * logic here prevents the controller and JavaScript from having to guess where
 * intro files live or how a stored walk-up filename becomes a public URL.
 */
@Service
public class WalkUpSongService {

    private final TeamRosterEntryRepository rosterEntryRepository;
    private final Path uploadRoot;

    public WalkUpSongService(TeamRosterEntryRepository rosterEntryRepository,
                             @Value("${app.uploads.root-path:${APP_UPLOADS_ROOT_PATH:/app/uploads}}") String uploadsRootPath) {
        this.rosterEntryRepository = rosterEntryRepository;
        this.uploadRoot = Path.of(uploadsRootPath).toAbsolutePath().normalize();
    }

    /**
     * Builds the current-batter DTO from the live GameState.
     *
     * This method is the important fix for the same-at-bat Next Batter highlight
     * problem. The AJAX endpoint must return the current batter's exact
     * TeamRosterEntry id after advancement, not just the player name/audio data.
     */
    public WalkUpSongInfo getWalkUpSongInfo(GameState gameState) {
        if (gameState == null || gameState.getCurrentBatterRosterEntry() == null) {
            return null;
        }

        WalkUpSongInfo info = getWalkUpSongInfo(gameState.getCurrentBatterRosterEntry().getId());
        info.setInning(gameState.getInning());

        Team battingTeam = gameState.getCurrentBattingTeam();
        if (battingTeam != null) {
            info.setBattingTeamId(battingTeam.getId());
            info.setBattingTeamColor(battingTeam.getColor());
        }

        return info;
    }

    /**
     * Looks up walk-up audio and roster identity for a roster entry. This is
     * used for the current batter on the live manager dashboard.
     */
    public WalkUpSongInfo getWalkUpSongInfo(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));

        WalkUpSongInfo info = getWalkUpSongInfoForPlayer(entry.getPlayer());
        info.setRosterEntryId(entry.getId());

        if (entry.getTeam() != null) {
            info.setBattingTeamId(entry.getTeam().getId());
            info.setBattingTeamColor(entry.getTeam().getColor());
        }

        return info;
    }

    /**
     * Builds player-level audio metadata. This is also used by manager upload
     * pages where there may not be an active roster entry.
     */
    public WalkUpSongInfo getWalkUpSongInfoForPlayer(Player player) {
        WalkUpSongInfo info = new WalkUpSongInfo();
        info.setPlayerId(player.getId());
        info.setPlayerName(displayName(player));
        info.setArtist(player.getWalkUpSongArtist());
        info.setTitle(player.getWalkUpSongTitle());

        applyIntroAudio(player, info);
        applyWalkUpSongAudio(player, info);

        return info;
    }

    /**
     * Intro clips are discovered by convention and are not stored in the DB:
     *
     *   /app/uploads/walkup-intros/{playerId}.mp3
     *
     * Public browser URL:
     *
     *   /uploads/walkup-intros/{playerId}.mp3
     */
    private void applyIntroAudio(Player player, WalkUpSongInfo info) {
        if (player.getId() == null) {
            info.setIntroPlayable(false);
            return;
        }

        Path introFile = uploadRoot
                .resolve("walkup-intros")
                .resolve(player.getId() + ".mp3")
                .normalize();

        if (Files.exists(introFile) && Files.isRegularFile(introFile)) {
            info.setIntroAudioUrl("/uploads/walkup-intros/" + player.getId() + ".mp3");
            info.setIntroPlayable(true);
        } else {
            info.setIntroPlayable(false);
        }
    }

    /**
     * Walk-up song clips are linked from the Player database record. The upload
     * page stores a filename in walkUpSongFilePath, but this method also supports
     * absolute URLs and already-rooted /uploads URLs.
     */
    private void applyWalkUpSongAudio(Player player, WalkUpSongInfo info) {
        String filePath = player.getWalkUpSongFilePath();
        if (filePath != null && !filePath.isBlank()) {
            info.setAudioUrl(toBrowserUrl(filePath));
            info.setPlayable(true);
        } else {
            info.setPlayable(false);
        }
    }

    /**
     * Converts a stored file path into something the browser can request.
     *
     * Supported examples:
     * - https://example.com/song.mp3       -> returned as-is
     * - /uploads/walkup-songs/song.mp3    -> returned as-is
     * - song.mp3                          -> /uploads/walkup-songs/song.mp3
     */
    private String toBrowserUrl(String filePath) {
        if (filePath.startsWith("http://") || filePath.startsWith("https://") || filePath.startsWith("/")) {
            return filePath;
        }
        return "/uploads/walkup-songs/" + filePath;
    }

    private String displayName(Player player) {
        return player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName();
    }
}
