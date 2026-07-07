package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongInfo;
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
 * The service returns browser URLs only when files actually exist. That keeps
 * the JavaScript simple: play intro if present, then play song if present.
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
     * Looks up walk-up audio for a roster entry. This is used when the current
     * batter is represented by a TeamRosterEntry id.
     */
    public WalkUpSongInfo getWalkUpSongInfo(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));

        return getWalkUpSongInfoForPlayer(entry.getPlayer());
    }

    /**
     * Builds the DTO from a player record and convention-based intro file.
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
