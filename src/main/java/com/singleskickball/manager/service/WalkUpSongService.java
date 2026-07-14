package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides walk-up audio metadata for the manager game screen.
 *
 * This service also includes roster/team identifiers in the returned DTO. Those
 * ids are important for the AJAX Next Batter flow because the page does not
 * reload after the current batter changes; JavaScript uses the ids to move the
 * highlighted "At Bat" badge to the correct roster row.
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
     * Looks up walk-up audio and roster identity for a roster entry. This is
     * used for the current batter on the live manager dashboard.
     */
    public WalkUpSongInfo getWalkUpSongInfo(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));

        WalkUpSongInfo info = getWalkUpSongInfoForPlayer(entry.getPlayer());

        // These fields are the key fix for the Next Batter highlight issue.
        // The browser can now select the exact roster row instead of guessing
        // from player data attributes that may also exist on buttons.
        info.setRosterEntryId(entry.getId());
        if (entry.getTeam() != null) {
            info.setBattingTeamId(entry.getTeam().getId());
            info.setBattingTeamColor(entry.getTeam().getColor());
        }

        return info;
    }

    /**
     * Convenience overload for live game-state callers.
     *
     * Keeping the conversion here avoids controllers reaching through several
     * entity relationships merely to obtain the current roster-entry id.
     */
    public WalkUpSongInfo getWalkUpSongInfo(GameState gameState) {
        if (gameState == null || gameState.getCurrentBatterRosterEntry() == null) {
            throw new IllegalArgumentException("No current batter is available.");
        }
        return getWalkUpSongInfo(gameState.getCurrentBatterRosterEntry().getId());
    }

    /**
     * Builds player-level audio metadata. This is also useful for admin/test
     * screens where there may not be an active roster entry.
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
     * Intro clips are not stored in the database. They are discovered by this
     * filename convention: /app/uploads/walkup-intros/{playerId}.mp3.
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
     * Walk-up song clips are linked from the Player database record.
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
     * - https://example.com/song.mp3                  -> returned as-is
     * - /uploads/walkup-songs/song.mp3               -> returned as-is
     * - song.mp3                                     -> /uploads/walkup-songs/song.mp3
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
