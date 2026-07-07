package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.RosterGenerationSummary;
import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds weekly rosters from availability, manager assignments, and teammate
 * preferences.
 *
 * Design priorities, in order:
 * 1. Create the correct number of teams.
 * 2. Put one manager/player on each team.
 * 3. Keep gender counts as even as possible across teams.
 * 4. Keep team sizes as even as possible.
 * 5. Honor mutual teammate preferences when doing so does not badly hurt balance.
 *
 * The algorithm is intentionally readable and deterministic. That matters more
 * than mathematical perfection at this stage because managers need to understand
 * and trust the result. We can later replace the scoring function with a more
 * formal optimizer without changing the rest of the application.
 */
@Service
public class RosterBuilderService {

    private static final List<String> DEFAULT_TEAM_COLORS = List.of("Yellow", "Red");

    private final PlayerAvailabilityRepository availabilityRepository;
    private final PlayerPreferenceRepository preferenceRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;
    private final GameStateRepository gameStateRepository;

    public RosterBuilderService(PlayerAvailabilityRepository availabilityRepository,
                                PlayerPreferenceRepository preferenceRepository,
                                PlayerRepository playerRepository,
                                TeamRepository teamRepository,
                                TeamRosterEntryRepository rosterEntryRepository,
                                GameStateRepository gameStateRepository) {
        this.availabilityRepository = availabilityRepository;
        this.preferenceRepository = preferenceRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
        this.gameStateRepository = gameStateRepository;
    }

    /**
     * Rebuilds the roster for the supplied week.
     *
     * This method clears existing roster/game-state data for the week because it
     * represents a manager explicitly asking the app to auto-create rosters.
     * Once a game is in progress, the UI should avoid calling this method unless
     * the manager intentionally wants to start over.
     */
    @Transactional
    public RosterGenerationSummary generateDefaultRoster(GameWeek week) {
        RosterGenerationSummary summary = new RosterGenerationSummary();

        clearExistingRosterForWeek(week);

        List<Team> teams = createTeams(week, DEFAULT_TEAM_COLORS);
        summary.setTeamsCreated(teams.size());

        Map<Long, TeamStats> statsByTeamId = teams.stream()
                .collect(Collectors.toMap(Team::getId, TeamStats::new, (a, b) -> a, LinkedHashMap::new));

        Set<Long> assignedPlayerIds = new HashSet<>();
        List<Player> activeManagers = playerRepository.findByManagerTrueAndActiveTrueOrderByNameAsc();

        // One manager goes on each team first. The app assumes the manager count
        // equals the team count, but this guard keeps the app safe if that changes.
        for (int i = 0; i < teams.size() && i < activeManagers.size(); i++) {
            assignPlayer(teams.get(i), activeManagers.get(i), statsByTeamId.get(teams.get(i).getId()));
            assignedPlayerIds.add(activeManagers.get(i).getId());
            summary.setPlayersAssigned(summary.getPlayersAssigned() + 1);
        }

        List<Player> availablePlayers = availabilityRepository
                .findByGameWeekAndStatus(week, AvailabilityStatus.IN)
                .stream()
                .map(PlayerAvailability::getPlayer)
                .filter(Player::isActive)
                .filter(player -> !assignedPlayerIds.contains(player.getId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<Long, Player> availableById = availablePlayers.stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        List<PlayerPreference> preferences = preferenceRepository.findByGameWeek(week);
        Map<Long, Long> preferredByPlayerId = preferences.stream()
                .collect(Collectors.toMap(p -> p.getPlayer().getId(), p -> p.getPreferredPlayer().getId(), (a, b) -> a));

        List<List<Player>> assignmentGroups = buildAssignmentGroups(availablePlayers, availableById, preferredByPlayerId, summary);

        for (List<Player> group : assignmentGroups) {
            Team bestTeam = chooseBestTeamForGroup(teams, statsByTeamId, group);
            TeamStats teamStats = statsByTeamId.get(bestTeam.getId());
            for (Player player : group) {
                assignPlayer(bestTeam, player, teamStats);
                summary.setPlayersAssigned(summary.getPlayersAssigned() + 1);
            }
        }

        summary.addNote("Rosters created for " + teams.size() + " teams.");
        return summary;
    }

    private void clearExistingRosterForWeek(GameWeek week) {
        gameStateRepository.findByGameWeek(week).ifPresent(gameStateRepository::delete);
        rosterEntryRepository.deleteAll(rosterEntryRepository.findByTeam_GameWeek(week));
        teamRepository.deleteAll(teamRepository.findByGameWeekOrderByIdAsc(week));
    }

    private List<Team> createTeams(GameWeek week, List<String> colors) {
        List<Team> teams = new ArrayList<>();
        for (String color : colors) {
            Team team = new Team();
            team.setGameWeek(week);
            team.setColor(color);
            team.setName(color + " Team");
            teams.add(team);
        }
        return teamRepository.saveAll(teams);
    }

    private List<List<Player>> buildAssignmentGroups(List<Player> availablePlayers,
                                                     Map<Long, Player> availableById,
                                                     Map<Long, Long> preferredByPlayerId,
                                                     RosterGenerationSummary summary) {
        List<List<Player>> groups = new ArrayList<>();
        Set<Long> groupedPlayerIds = new HashSet<>();

        for (Player player : availablePlayers) {
            if (groupedPlayerIds.contains(player.getId())) {
                continue;
            }

            Long preferredId = preferredByPlayerId.get(player.getId());
            Player preferredPlayer = preferredId == null ? null : availableById.get(preferredId);

            boolean mutual = preferredPlayer != null
                    && Objects.equals(preferredByPlayerId.get(preferredPlayer.getId()), player.getId())
                    && !groupedPlayerIds.contains(preferredPlayer.getId());

            if (mutual) {
                groups.add(List.of(player, preferredPlayer));
                groupedPlayerIds.add(player.getId());
                groupedPlayerIds.add(preferredPlayer.getId());
                summary.setMutualPreferencesHonored(summary.getMutualPreferencesHonored() + 1);
            } else {
                groups.add(List.of(player));
                groupedPlayerIds.add(player.getId());
            }
        }

        return groups;
    }

    private Team chooseBestTeamForGroup(List<Team> teams, Map<Long, TeamStats> statsByTeamId, List<Player> group) {
        return teams.stream()
                .min(Comparator
                        .comparingInt((Team team) -> scoreTeamAfterAddingGroup(statsByTeamId.get(team.getId()), group))
                        .thenComparing(Team::getId))
                .orElseThrow(() -> new IllegalStateException("No teams were created."));
    }

    private int scoreTeamAfterAddingGroup(TeamStats stats, List<Player> group) {
        int projectedSize = stats.size;
        int projectedMale = stats.maleCount;
        int projectedFemale = stats.femaleCount;

        for (Player player : group) {
            projectedSize++;
            if (player.getGender() == Gender.FEMALE) {
                projectedFemale++;
            } else if (player.getGender() == Gender.MALE) {
                projectedMale++;
            }
        }

        int genderImbalance = Math.abs(projectedMale - projectedFemale);

        // Lower is better. Gender imbalance gets more weight than raw team size.
        return (genderImbalance * 5) + (projectedSize * 2);
    }

    private void assignPlayer(Team team, Player player, TeamStats stats) {
        TeamRosterEntry entry = new TeamRosterEntry();
        entry.setTeam(team);
        entry.setPlayer(player);
        entry.setBattingOrder(stats.size + 1);
        entry.setRunsScored(0);
        rosterEntryRepository.save(entry);
        stats.add(player);
    }

    private static class TeamStats {
        private final Team team;
        private int size;
        private int maleCount;
        private int femaleCount;

        private TeamStats(Team team) {
            this.team = team;
        }

        private void add(Player player) {
            size++;
            if (player.getGender() == Gender.FEMALE) {
                femaleCount++;
            } else if (player.getGender() == Gender.MALE) {
                maleCount++;
            }
        }
    }
}
