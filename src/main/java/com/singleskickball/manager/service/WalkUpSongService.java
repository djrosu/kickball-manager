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
 * Mobile Safari and most modern browsers usually require a user gesture before
 * audio can play. That means we should design the UI around a manager tapping a
 * "Play Walk-Up Song" button rather than expecting true automatic playback.
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
     * Converts the stored file path into something the browser can request.
     *
     * Supported examples:
     * - https://example.com/song.mp3      -> returned as-is
     * - /walkup-songs/song.mp3           -> returned as-is
     * - song.mp3                         -> /walkup-songs/song.mp3
     */
    private String toBrowserUrl(String filePath) {
        if (filePath.startsWith("http://") || filePath.startsWith("https://") || filePath.startsWith("/")) {
            return filePath;
        }
        return "/walkup-songs/" + filePath;
    }
}
