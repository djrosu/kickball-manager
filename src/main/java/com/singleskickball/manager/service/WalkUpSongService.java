package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.stereotype.Service;

/**
 * Provides walk-up song metadata for the manager screen.
 *
 * Browser limitation:
 * Mobile Safari and most modern browsers require a user gesture before audio can
 * play. The manager dashboard therefore uses a visible "Play Walk-Up Song"
 * button rather than relying on true autoplay.
 */
@Service
public class WalkUpSongService {

    private final TeamRosterEntryRepository rosterEntryRepository;

    public WalkUpSongService(TeamRosterEntryRepository rosterEntryRepository) {
        this.rosterEntryRepository = rosterEntryRepository;
    }

    public WalkUpSongInfo getWalkUpSongInfo(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));

        Player player = entry.getPlayer();
        WalkUpSongInfo info = new WalkUpSongInfo();
        info.setPlayerId(player.getId());
        info.setPlayerName(player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName());
        info.setArtist(player.getWalkUpSongArtist());
        info.setTitle(player.getWalkUpSongTitle());

        String filePath = player.getWalkUpSongFilePath();
        if (filePath != null && !filePath.isBlank()) {
            info.setAudioUrl(toBrowserUrl(filePath));
            info.setPlayable(true);
        } else {
            info.setPlayable(false);
        }

        return info;
    }

    /**
     * Converts the stored file path into a browser URL.
     *
     * Supported examples:
     * - https://example.com/song.mp3                         -> returned as-is
     * - /uploads/walkup-songs/player-26-diesel.mp3          -> returned as-is
     * - player-26-diesel.mp3                                -> /uploads/walkup-songs/player-26-diesel.mp3
     */
    private String toBrowserUrl(String filePath) {
        if (filePath.startsWith("http://") || filePath.startsWith("https://") || filePath.startsWith("/")) {
            return filePath;
        }
        return "/uploads/walkup-songs/" + filePath;
    }
}
