package com.singleskickball.manager.service;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.GameWeekRepository;
import com.singleskickball.manager.repository.PlayerAvailabilityRepository;
import com.singleskickball.manager.repository.PlayerPreferenceRepository;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
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
 *
 * Important production note:
 * Railway/container servers may run in UTC. Columbus games are scheduled using
 * Eastern time, so this service uses app.time-zone, defaulting to
 * America/New_York, instead of LocalDate.now() in the server JVM's default zone.
 */
@Service
public class GameWeekService {

    private final GameWeekRepository gameWeekRepository;
    private final PlayerAvailabilityRepository availabilityRepository;
    private final PlayerPreferenceRepository preferenceRepository;
    private final PlayerRepository playerRepository;
    private final ZoneId leagueZoneId;

    public GameWeekService(GameWeekRepository gameWeekRepository,
                           PlayerAvailabilityRepository availabilityRepository,
                           PlayerPreferenceRepository preferenceRepository,
                           PlayerRepository playerRepository,
                           @Value("${app.time-zone:America/New_York}") String leagueTimeZone) {
        this.gameWeekRepository = gameWeekRepository;
        this.availabilityRepository = availabilityRepository;
        this.preferenceRepository = preferenceRepository;
        this.playerRepository = playerRepository;
        this.leagueZoneId = ZoneId.of(leagueTimeZone);
    }

    /**
     * Returns the game week the app should currently show.
     *
     * Selection order:
     * 1. Any IN_PROGRESS game, regardless of date. This prevents the dashboard
     *    from jumping to next week during a late game.
     * 2. The next non-final game on or after today's date in league time.
     * 3. The latest scheduled game as a last-resort fallback.
     */
    public GameWeek getCurrentGameWeek() {
        return gameWeekRepository
                .findFirstByStatusOrderByGameDateAsc(GameWeekStatus.IN_PROGRESS)
                .orElseGet(() -> {
                    LocalDate todayInLeagueTime = LocalDate.now(leagueZoneId);
                    return gameWeekRepository
                            .findFirstByStatusNotAndGameDateGreaterThanEqualOrderByGameDateAsc(
                                    GameWeekStatus.FINAL,
                                    todayInLeagueTime
                            )
                            .orElseGet(() -> gameWeekRepository.findAll()
                                    .stream()
                                    .max(Comparator.comparing(GameWeek::getGameDate))
                                    .orElseThrow(() -> new IllegalStateException("No game weeks have been created.")));
                });
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
