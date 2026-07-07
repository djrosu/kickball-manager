package com.singleskickball.manager.service;

import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Player-related business operations. Controllers should use this service
 * instead of reaching directly into repositories for profile updates.
 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public Player getCurrentPlayer(Authentication authentication) {
        return playerRepository.findByEmailIgnoreCase(authentication.getName())
            .orElseThrow(() -> new IllegalStateException("Logged-in player was not found."));
    }

    public List<Player> findActivePlayers() {
        return playerRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional
    public void updateProfile(Player player, String nickname, String artist, String title) {
        player.setNickname(nickname);
        player.setWalkUpSongArtist(artist);
        player.setWalkUpSongTitle(title);
        playerRepository.save(player);
    }
}
