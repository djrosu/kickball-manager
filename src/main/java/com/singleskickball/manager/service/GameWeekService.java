package com.singleskickball.manager.service;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.GameWeekRepository;
import com.singleskickball.manager.repository.PlayerAvailabilityRepository;
import com.singleskickball.manager.repository.PlayerPreferenceRepository;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Business service for weekly game data.
 *
 * This service owns the logic for:
 * - finding the current/upcoming game week
 * - saving player availability
 * - saving preferred teammate requests
 */
@Service
public class GameWeekService {

    private final GameWeekRepository gameWeekRepository;
    private final PlayerAvailabilityRepository availabilityRepository;
    private final PlayerPreferenceRepository preferenceRepository;
    private final PlayerRepository playerRepository;

    public GameWeekService(GameWeekRepository gameWeekRepository,
                           PlayerAvailabilityRepository availabilityRepository,
                           PlayerPreferenceRepository preferenceRepository,
                           PlayerRepository playerRepository) {
        this.gameWeekRepository = gameWeekRepository;
        this.availabilityRepository = availabilityRepository;
        this.preferenceRepository = preferenceRepository;
        this.playerRepository = playerRepository;
    }

    public GameWeek getCurrentGameWeek() {
        return gameWeekRepository
                .findFirstByGameDateGreaterThanEqualOrderByGameDateAsc(LocalDate.now())
                .orElseGet(() -> gameWeekRepository.findAll()
                        .stream()
                        .max(Comparator.comparing(GameWeek::getGameDate))
                        .orElseThrow(() -> new IllegalStateException("No game weeks have been created.")));
    }

    public List<PlayerAvailability> getAvailabilityForWeek(GameWeek week) {
        return availabilityRepository.findByGameWeek(week);
    }

    public Optional<AvailabilityStatus> getAvailabilityStatus(GameWeek week, Player player) {
        return availabilityRepository.findByGameWeekAndPlayer(week, player)
                .map(PlayerAvailability::getStatus);
    }

    public Optional<Long> getPreferredPlayerId(GameWeek week, Player player) {
        return preferenceRepository.findByGameWeekAndPlayer(week, player)
                .map(PlayerPreference::getPreferredPlayer)
                .map(Player::getId);
    }

    @Transactional
    public void setAvailability(GameWeek week, Player player, AvailabilityStatus status) {
        PlayerAvailability availability = availabilityRepository
                .findByGameWeekAndPlayer(week, player)
                .orElseGet(() -> {
                    PlayerAvailability created = new PlayerAvailability();
                    created.setGameWeek(week);
                    created.setPlayer(player);
                    return created;
                });

        availability.setStatus(status);
        availabilityRepository.save(availability);
    }

    @Transactional
    public void setPreference(GameWeek week, Player player, Long preferredPlayerId) {
        Player preferredPlayer = playerRepository.findById(preferredPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Preferred player not found."));

        PlayerPreference preference = preferenceRepository
                .findByGameWeekAndPlayer(week, player)
                .orElseGet(() -> {
                    PlayerPreference created = new PlayerPreference();
                    created.setGameWeek(week);
                    created.setPlayer(player);
                    return created;
                });

        preference.setPreferredPlayer(preferredPlayer);
        preferenceRepository.save(preference);
    }
}