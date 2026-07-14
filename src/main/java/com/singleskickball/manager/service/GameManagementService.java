package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.TeamScore;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.GameWeekStatus;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.GameStateRepository;
import com.singleskickball.manager.repository.GameWeekRepository;
import com.singleskickball.manager.repository.TeamRepository;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Owns live game operations.
 *
 * This service tracks which team is batting, which player is currently up, run
 * scoring, and switching sides. Per-team batting positions are intentionally
 * kept in memory instead of adding another production database table.
 *
 * Why in memory?
 * - Batting-position progress only matters while a live game is being managed.
 * - Rosters are regenerated each week.
 * - We do not need historical persistence for where a lineup was mid-game.
 *
 * The durable game_state table still stores the visible current batting team,
 * current batter, and inning. The in-memory map only remembers each team's last
 * batter while switching between teams during an active server process.
 */
@Service
public class GameManagementService {

    private final GameWeekRepository gameWeekRepository;
    private final GameStateRepository gameStateRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;

    /**
     * week id -> team id -> current roster-entry id for that team's batting order.
     */
    private final Map<Long, Map<Long, Long>> currentBatterByWeekAndTeam = new ConcurrentHashMap<>();

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
     * Starts or restarts live game tracking for the selected week.
     *
     * The first team in database order bats first. Each team's in-memory batting
     * position is initialized to the first player in that team's batting order.
     */
    @Transactional
    public GameState startGame(GameWeek week) {
        List<Team> teams = teamRepository.findByGameWeekOrderByIdAsc(week);
        if (teams.isEmpty()) {
            throw new IllegalStateException("Create rosters before starting the game.");
        }

        initializeInMemoryBattingPositions(week, teams);

        Team firstTeam = teams.get(0);
        TeamRosterEntry firstBatter = currentBatterForTeam(week, firstTeam)
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
     * The key detail: each team's position is remembered separately in memory,
     * so when teams switch at-bats and later switch back, the returning team
     * resumes where it left off instead of starting over at the top.
     */
    @Transactional
    public GameState nextBatter(GameWeek week) {
        GameState state = requireGameState(week);
        Team battingTeam = state.getCurrentBattingTeam();
        if (battingTeam == null) {
            throw new IllegalStateException("No batting team is selected.");
        }

        List<TeamRosterEntry> lineup = rosterEntryRepository.findByTeamOrderByBattingOrderAsc(battingTeam);
        if (lineup.isEmpty()) {
            throw new IllegalStateException("Current batting team has no players.");
        }

        TeamRosterEntry current = bestCurrentBatterForNextAdvance(week, battingTeam, state, lineup);
        int currentIndex = findRosterEntryIndex(lineup, current);
        int nextIndex = (currentIndex + 1) % lineup.size();
        TeamRosterEntry nextBatter = lineup.get(nextIndex);

        rememberCurrentBatter(week, battingTeam, nextBatter);
        state.setCurrentBatterRosterEntry(nextBatter);
        return gameStateRepository.save(state);
    }

    /**
     * Ends the current team's at-bat and switches to the next team.
     *
     * With two teams this alternates Red/Yellow. If more teams are added later,
     * the same logic cycles through all teams in database order. When the cycle
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

        /*
         * Do NOT advance the departing team's saved batter here.
         *
         * The player who most recently batted remains highlighted for that team
         * while the other team is at bat. When this team returns, the manager
         * presses Next Batter to advance exactly once and begin the new inning.
         */

        int currentIndex = findTeamIndex(teams, currentTeam);
        int nextIndex = (currentIndex + 1) % teams.size();

        if (currentIndex == teams.size() - 1) {
            state.setInning(state.getInning() + 1);
        }

        Team nextTeam = teams.get(nextIndex);
        TeamRosterEntry nextTeamCurrentBatter = currentBatterForTeam(week, nextTeam)
                .orElseGet(() -> rosterEntryRepository.findFirstByTeamOrderByBattingOrderAsc(nextTeam).orElse(null));

        state.setCurrentBattingTeam(nextTeam);
        state.setCurrentBatterRosterEntry(nextTeamCurrentBatter);
        return gameStateRepository.save(state);
    }


    /**
     * Ends the live game and marks the week final.
     *
     * This intentionally keeps teams, roster entries, batting order, and run
     * totals. Those rows are the season history and are used by the running run
     * leaderboard and next week's roster balancing.
     */
    @Transactional
    public void endGame(GameWeek week) {
        gameStateRepository.findByGameWeek(week).ifPresent(gameStateRepository::delete);
        currentBatterByWeekAndTeam.remove(week.getId());

        week.setStatus(GameWeekStatus.FINAL);
        gameWeekRepository.save(week);
    }

    /**
     * Resumes or starts live tracking for a selected game week.
     *
     * This is primarily used from the League Supervisor Game Administration
     * section. It is safe for recovering from an accidental End Game click:
     * existing teams, batting order, and run totals are preserved. If the prior
     * game_state row was deleted by End Game, live tracking is recreated from
     * the first team/first batter.
     */
    @Transactional
    public GameState resumeGame(GameWeek week) {
        return startGame(week);
    }

    /**
     * Restarts live game tracking using the existing teams and batting order.
     *
     * This does not delete teams or reset run totals. It simply puts the game
     * back into IN_PROGRESS status and resets the visible at-bat state to the
     * first team/first batter. That makes it safe for recovering from an
     * accidental End Game click without losing the scorebook.
     */
    @Transactional
    public GameState restartGame(GameWeek week) {
        return startGame(week);
    }

    /**
     * Reopens a game for player availability.
     *
     * This is a supervisor recovery tool. It removes only the live game_state
     * row and changes the week status back to OPEN_FOR_AVAILABILITY. It does
     * not delete players, availability selections, preferences, rosters, or run
     * totals.
     */
    @Transactional
    public void reopenForAvailability(GameWeek week) {
        gameStateRepository.findByGameWeek(week).ifPresent(gameStateRepository::delete);
        currentBatterByWeekAndTeam.remove(week.getId());
        week.setStatus(GameWeekStatus.OPEN_FOR_AVAILABILITY);
        gameWeekRepository.save(week);
    }

    /** Adds one run to the selected player/roster entry. */
    @Transactional
    public void addRun(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
        entry.setRunsScored(entry.getRunsScored() + 1);
        rosterEntryRepository.save(entry);
    }

    /**
     * Removes one run from the selected player/roster entry.
     *
     * The count never goes below zero. This gives scorekeepers a quick way to
     * correct an accidental tap without directly editing the database.
     */
    @Transactional
    public void removeRun(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
        entry.setRunsScored(Math.max(0, entry.getRunsScored() - 1));
        rosterEntryRepository.save(entry);
    }

    /** Returns current score by team based on roster entry run totals. */
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

    private void initializeInMemoryBattingPositions(GameWeek week, List<Team> teams) {
        Map<Long, Long> positions = new ConcurrentHashMap<>();
        for (Team team : teams) {
            rosterEntryRepository.findFirstByTeamOrderByBattingOrderAsc(team)
                    .ifPresent(entry -> positions.put(team.getId(), entry.getId()));
        }
        currentBatterByWeekAndTeam.put(week.getId(), positions);
    }

    private TeamRosterEntry bestCurrentBatterForNextAdvance(GameWeek week,
                                                           Team battingTeam,
                                                           GameState state,
                                                           List<TeamRosterEntry> lineup) {
        TeamRosterEntry visibleCurrent = state.getCurrentBatterRosterEntry();
        if (visibleCurrent != null
                && visibleCurrent.getTeam() != null
                && Objects.equals(visibleCurrent.getTeam().getId(), battingTeam.getId())
                && findRosterEntryIndex(lineup, visibleCurrent) >= 0) {
            return visibleCurrent;
        }

        return currentBatterForTeam(week, battingTeam).orElse(null);
    }

    private Optional<TeamRosterEntry> currentBatterForTeam(GameWeek week, Team team) {
        Long rosterEntryId = currentBatterByWeekAndTeam
                .getOrDefault(week.getId(), Map.of())
                .get(team.getId());

        if (rosterEntryId != null) {
            Optional<TeamRosterEntry> saved = rosterEntryRepository.findById(rosterEntryId);
            if (saved.isPresent() && saved.get().getTeam() != null
                    && Objects.equals(saved.get().getTeam().getId(), team.getId())) {
                return saved;
            }
        }

        return rosterEntryRepository.findFirstByTeamOrderByBattingOrderAsc(team);
    }

    /**
     * Stores the next batter for a team when that team's at-bat ends.
     *
     * This is intentionally different from rememberCurrentBatter(...). The
     * current visible batter is the last batter shown during the current
     * half-inning. When this team bats again, the lineup should resume with the
     * following player.
     */
    private void rememberNextBatterForTeam(GameWeek week, Team team, TeamRosterEntry currentBatter) {
        if (week == null || team == null || currentBatter == null) {
            return;
        }

        List<TeamRosterEntry> lineup = rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
        if (lineup.isEmpty()) {
            return;
        }

        int currentIndex = findRosterEntryIndex(lineup, currentBatter);
        int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % lineup.size();
        rememberCurrentBatter(week, team, lineup.get(nextIndex));
    }

    private void rememberCurrentBatter(GameWeek week, Team team, TeamRosterEntry batter) {
        if (week == null || week.getId() == null || team == null || team.getId() == null || batter == null || batter.getId() == null) {
            return;
        }

        currentBatterByWeekAndTeam
                .computeIfAbsent(week.getId(), ignored -> new ConcurrentHashMap<>())
                .put(team.getId(), batter.getId());
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
            return 0;
        }
        for (int i = 0; i < teams.size(); i++) {
            if (Objects.equals(teams.get(i).getId(), currentTeam.getId())) {
                return i;
            }
        }
        return 0;
    }

    private GameState requireGameState(GameWeek week) {
        return gameStateRepository.findByGameWeek(week)
                .orElseThrow(() -> new IllegalStateException("Start the game before using live game controls."));
    }
}
