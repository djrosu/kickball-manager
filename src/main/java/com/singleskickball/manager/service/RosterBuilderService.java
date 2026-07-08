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
 * Builds weekly rosters from availability, manager assignments, teammate
 * preferences, and prior season run totals.
 *
 * Design priorities, in order:
 * 1. Create the correct number of teams.
 * 2. Put one manager/player on each team.
 * 3. Keep gender counts as even as possible across teams.
 * 4. Keep team sizes as even as possible.
 * 5. Gently split high run scorers across teams.
 * 6. Honor mutual teammate preferences when doing so does not badly hurt balance.
 *
 * The algorithm is intentionally readable and deterministic. That matters more
 * than mathematical perfection at this stage because managers need to understand
 * and trust the result. We can later replace the scoring function with a more
 * formal optimizer without changing the rest of the application.
 */
@Service
public class RosterBuilderService {

    private static final List<String> DEFAULT_TEAM_COLORS = List.of("Red", "Yellow");

    private final PlayerAvailabilityRepository availabilityRepository;
    private final PlayerPreferenceRepository preferenceRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;
    private final GameStateRepository gameStateRepository;
    private final GameWeekRepository gameWeekRepository;

    public RosterBuilderService(PlayerAvailabilityRepository availabilityRepository,
                                PlayerPreferenceRepository preferenceRepository,
                                PlayerRepository playerRepository,
                                TeamRepository teamRepository,
                                TeamRosterEntryRepository rosterEntryRepository,
                                GameStateRepository gameStateRepository,
                                GameWeekRepository gameWeekRepository) {
        this.availabilityRepository = availabilityRepository;
        this.preferenceRepository = preferenceRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
        this.gameStateRepository = gameStateRepository;
        this.gameWeekRepository = gameWeekRepository;
    }

    /**
     * Rebuilds the roster for the supplied week.
     *
     * This method clears existing roster/game-state data for the week because it
     * represents a manager explicitly asking the app to auto-create rosters.
     * The dashboard disables this action once a game starts.
     */
    @Transactional
    public RosterGenerationSummary generateDefaultRoster(GameWeek week) {
        RosterGenerationSummary summary = new RosterGenerationSummary();

        clearExistingRosterForWeek(week);

        List<Team> teams = createTeams(week, DEFAULT_TEAM_COLORS);
        summary.setTeamsCreated(teams.size());

        Map<Long, Integer> seasonRunsByPlayerId = loadSeasonRunTotals();
        Map<Long, TeamStats> statsByTeamId = teams.stream()
                .collect(Collectors.toMap(Team::getId, TeamStats::new, (a, b) -> a, LinkedHashMap::new));

        Set<Long> assignedPlayerIds = new HashSet<>();

        // Put one manager on each team. Managers are also sorted by season runs
        // so if one manager is a high scorer, that run strength starts on a
        // different team than the next high manager when possible.
        List<Player> activeManagers = playerRepository.findByManagerTrueAndActiveTrueOrderByNameAsc()
                .stream()
                .sorted(playerStrengthComparator(seasonRunsByPlayerId))
                .toList();

        for (int i = 0; i < teams.size() && i < activeManagers.size(); i++) {
            Player manager = activeManagers.get(i);
            assignPlayer(teams.get(i), manager, statsByTeamId.get(teams.get(i).getId()), seasonRunsByPlayerId);
            assignedPlayerIds.add(manager.getId());
            summary.setPlayersAssigned(summary.getPlayersAssigned() + 1);
        }

        List<Player> availablePlayers = availabilityRepository
                .findByGameWeekAndStatus(week, AvailabilityStatus.IN)
                .stream()
                .map(PlayerAvailability::getPlayer)
                .filter(Player::isActive)
                .filter(player -> !assignedPlayerIds.contains(player.getId()))
                .sorted(playerStrengthComparator(seasonRunsByPlayerId))
                .toList();

        Map<Long, Player> availableById = availablePlayers.stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        List<PlayerPreference> preferences = preferenceRepository.findByGameWeek(week);
        Map<Long, Long> preferredByPlayerId = preferences.stream()
                .collect(Collectors.toMap(
                        p -> p.getPlayer().getId(),
                        p -> p.getPreferredPlayer().getId(),
                        (a, b) -> a
                ));

        List<List<Player>> assignmentGroups = buildAssignmentGroups(availablePlayers, availableById, preferredByPlayerId, summary);

        // Assign stronger groups first so the run-total balancing has a chance
        // to split the biggest run contributors across teams.
        assignmentGroups = assignmentGroups.stream()
                .sorted(Comparator
                        .comparingInt((List<Player> group) -> groupSeasonRuns(group, seasonRunsByPlayerId))
                        .reversed()
                        .thenComparing(group -> group.stream().map(Player::getName).collect(Collectors.joining("|"))))
                .toList();

        for (List<Player> group : assignmentGroups) {
            Team bestTeam = chooseBestTeamForGroup(teams, statsByTeamId, group, seasonRunsByPlayerId);
            TeamStats teamStats = statsByTeamId.get(bestTeam.getId());
            for (Player player : group) {
                assignPlayer(bestTeam, player, teamStats, seasonRunsByPlayerId);
                summary.setPlayersAssigned(summary.getPlayersAssigned() + 1);
            }
        }

        week.setStatus(GameWeekStatus.ROSTERS_CREATED);
        gameWeekRepository.save(week);

        summary.addNote("Rosters created for " + teams.size() + " teams.");
        summary.addNote("Prior run totals were considered after gender and team-size balance.");
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

    private Team chooseBestTeamForGroup(List<Team> teams,
                                        Map<Long, TeamStats> statsByTeamId,
                                        List<Player> group,
                                        Map<Long, Integer> seasonRunsByPlayerId) {
        return teams.stream()
                .min(Comparator
                        .comparingInt((Team team) -> scoreTeamAfterAddingGroup(
                                statsByTeamId.get(team.getId()),
                                group,
                                seasonRunsByPlayerId
                        ))
                        .thenComparing(Team::getId))
                .orElseThrow(() -> new IllegalStateException("No teams were created."));
    }

    private int scoreTeamAfterAddingGroup(TeamStats stats,
                                          List<Player> group,
                                          Map<Long, Integer> seasonRunsByPlayerId) {
        int projectedSize = stats.size;
        int projectedMale = stats.maleCount;
        int projectedFemale = stats.femaleCount;
        int projectedSeasonRuns = stats.seasonRunTotal;

        for (Player player : group) {
            projectedSize++;
            projectedSeasonRuns += seasonRunsByPlayerId.getOrDefault(player.getId(), 0);
            if (player.getGender() == Gender.FEMALE) {
                projectedFemale++;
            } else if (player.getGender() == Gender.MALE) {
                projectedMale++;
            }
        }

        int genderImbalance = Math.abs(projectedMale - projectedFemale);

        // Lower is better.
        // Gender imbalance intentionally has the strongest weight. Prior runs
        // are considered, but they should not override a good male/female ratio.
        return (genderImbalance * 1000)
                + (projectedSize * 100)
                + projectedSeasonRuns;
    }

    private void assignPlayer(Team team,
                              Player player,
                              TeamStats stats,
                              Map<Long, Integer> seasonRunsByPlayerId) {
        TeamRosterEntry entry = new TeamRosterEntry();
        entry.setTeam(team);
        entry.setPlayer(player);
        entry.setBattingOrder(stats.size + 1);
        entry.setRunsScored(0);
        rosterEntryRepository.save(entry);
        stats.add(player, seasonRunsByPlayerId.getOrDefault(player.getId(), 0));
    }

    private Comparator<Player> playerStrengthComparator(Map<Long, Integer> seasonRunsByPlayerId) {
        return Comparator
                .comparingInt((Player player) -> seasonRunsByPlayerId.getOrDefault(player.getId(), 0))
                .reversed()
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER);
    }

    private int groupSeasonRuns(List<Player> group, Map<Long, Integer> seasonRunsByPlayerId) {
        return group.stream()
                .mapToInt(player -> seasonRunsByPlayerId.getOrDefault(player.getId(), 0))
                .sum();
    }

    private Map<Long, Integer> loadSeasonRunTotals() {
        return rosterEntryRepository.findPlayerRunTotals()
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Number) row[1]).intValue()
                ));
    }

    private static class TeamStats {
        private final Team team;
        private int size;
        private int maleCount;
        private int femaleCount;
        private int seasonRunTotal;

        private TeamStats(Team team) {
            this.team = team;
        }

        private void add(Player player, int playerSeasonRuns) {
            size++;
            seasonRunTotal += playerSeasonRuns;
            if (player.getGender() == Gender.FEMALE) {
                femaleCount++;
            } else if (player.getGender() == Gender.MALE) {
                maleCount++;
            }
        }
    }
}
