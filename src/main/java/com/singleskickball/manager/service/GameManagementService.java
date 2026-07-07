package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.TeamScore;
import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns live game operations.
 *
 * This service tracks which team is batting, which player is currently up, run
 * scoring, and switching sides. Keeping this logic out of the controller makes
 * it easier to later add a live scoreboard, WebSocket updates, or mobile app
 * endpoints without duplicating business rules.
 */
@Service
public class GameManagementService {

    private final GameWeekRepository gameWeekRepository;
    private final GameStateRepository gameStateRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public GameManagementService(GameWeekRepository gameWeekRepository,
                                 GameStateRepository gameStateRepository,
                                 TeamRepository teamRepository,
                                 TeamRosterEntryRepository rosterEntryRepository) {
        this.gameWeekRepository = gameWeekRepository;
        this.gameStateRepository = gameStateRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    public Optional<GameState> getGameState(GameWeek week) {
        return gameStateRepository.findByGameWeek(week);
    }

    /**
     * Starts or restarts live game tracking for a week.
     *
     * This does not create rosters. Rosters must exist first. The first team in
     * database order bats first and the first batter in that team's batting order
     * becomes the current batter.
     */
    @Transactional
    public GameState startGame(GameWeek week) {
        List<Team> teams = teamRepository.findByGameWeekOrderByIdAsc(week);
        if (teams.isEmpty()) {
            throw new IllegalStateException("Create rosters before starting the game.");
        }

        Team firstTeam = teams.get(0);
        TeamRosterEntry firstBatter = rosterEntryRepository.findFirstByTeamOrderByBattingOrderAsc(firstTeam)
                .orElseThrow(() -> new IllegalStateException("The first team has no batting order."));

        GameState state = gameStateRepository.findByGameWeek(week).orElseGet(() -> {
            GameState created = new GameState();
            created.setGameWeek(week);
            return created;
        });

        state.setInning(1);
        state.setCurrentBattingTeam(firstTeam);
        state.setCurrentBatterRosterEntry(firstBatter);

        week.setStatus(GameWeekStatus.IN_PROGRESS);
        gameWeekRepository.save(week);

        return gameStateRepository.save(state);
    }

    /**
     * Advances to the next batter on the current batting team.
     *
     * If the current batter is the last batter in the lineup, this wraps back to
     * the top of the same team's order. The manager should use endAtBat() when
     * the half-inning is over and it is time to switch teams.
     */
    @Transactional
    public GameState nextBatter(GameWeek week) {
        GameState state = requireGameState(week);
        Team battingTeam = state.getCurrentBattingTeam();
        List<TeamRosterEntry> lineup = rosterEntryRepository.findByTeamOrderByBattingOrderAsc(battingTeam);
        if (lineup.isEmpty()) {
            throw new IllegalStateException("Current batting team has no players.");
        }

        TeamRosterEntry current = state.getCurrentBatterRosterEntry();
        int currentIndex = findRosterEntryIndex(lineup, current);
        int nextIndex = (currentIndex + 1) % lineup.size();

        state.setCurrentBatterRosterEntry(lineup.get(nextIndex));
        return gameStateRepository.save(state);
    }

    /**
     * Ends the current team's at-bat and switches to the next team.
     *
     * For two teams, this simply alternates Red/Yellow. If more teams are added
     * later, this cycles through all teams in their saved order. When the cycle
     * returns to the first team, the inning counter advances.
     */
    @Transactional
    public GameState endAtBat(GameWeek week) {
        GameState state = requireGameState(week);
        List<Team> teams = teamRepository.findByGameWeekOrderByIdAsc(week);
        if (teams.isEmpty()) {
            throw new IllegalStateException("No teams exist for this game week.");
        }

        Team currentTeam = state.getCurrentBattingTeam();
        int currentIndex = findTeamIndex(teams, currentTeam);
        int nextIndex = (currentIndex + 1) % teams.size();

        if (currentIndex == teams.size() - 1) {
            state.setInning(state.getInning() + 1);
        }

        Team nextTeam = teams.get(nextIndex);
        state.setCurrentBattingTeam(nextTeam);
        state.setCurrentBatterRosterEntry(rosterEntryRepository.findFirstByTeamOrderByBattingOrderAsc(nextTeam).orElse(null));
        return gameStateRepository.save(state);
    }

    @Transactional
    public void addRun(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
        entry.setRunsScored(entry.getRunsScored() + 1);
        rosterEntryRepository.save(entry);
    }

    /**
     * Returns current score by team based on all roster entry run totals.
     */
    public List<TeamScore> getScores(GameWeek week) {
        Map<Long, Integer> totalsByTeamId = rosterEntryRepository.findTeamRunTotals(week)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Number) row[1]).intValue()
                ));

        return teamRepository.findByGameWeekOrderByIdAsc(week)
                .stream()
                .map(team -> new TeamScore(
                        team.getId(),
                        team.getColor(),
                        team.getName(),
                        totalsByTeamId.getOrDefault(team.getId(), 0)
                ))
                .toList();
    }

    private int findRosterEntryIndex(List<TeamRosterEntry> lineup, TeamRosterEntry current) {
        if (current == null || current.getId() == null) {
            return -1;
        }
        for (int i = 0; i < lineup.size(); i++) {
            if (Objects.equals(lineup.get(i).getId(), current.getId())) {
                return i;
            }
        }
        return -1;
    }

    private int findTeamIndex(List<Team> teams, Team currentTeam) {
        if (currentTeam == null || currentTeam.getId() == null) {
            return -1;
        }
        for (int i = 0; i < teams.size(); i++) {
            if (Objects.equals(teams.get(i).getId(), currentTeam.getId())) {
                return i;
            }
        }
        return -1;
    }

    private GameState requireGameState(GameWeek week) {
        return gameStateRepository.findByGameWeek(week)
                .orElseThrow(() -> new IllegalStateException("Start the game before using live game controls."));
    }
}
