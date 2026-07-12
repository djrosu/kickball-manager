package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.ManagerActionRequest;
import com.singleskickball.manager.dto.ManagerDashboardState;
import com.singleskickball.manager.dto.TeamScore;
import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import com.singleskickball.manager.service.GameManagementService;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.LineupService;
import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.PlayerService;
import com.singleskickball.manager.service.RosterService;
import com.singleskickball.manager.service.WalkUpSongService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * JSON API for high-frequency manager actions.
 *
 * The server-rendered manager pages remain responsible for the initial page
 * load, while this controller handles live mutations after the page is open.
 * Every successful endpoint returns a complete dashboard snapshot so the
 * browser can update itself without submitting a form or parsing replacement
 * HTML. The same API is used by League Supervisors and Team Managers; all
 * authorization is checked again on the server for every request.
 */
@RestController
@RequestMapping("/manager/api")
public class ManagerApiController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final PlayerService playerService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;
    private final WalkUpSongService walkUpSongService;
    private final ManagerAccessService accessService;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public ManagerApiController(GameWeekService gameWeekService,
                                RosterService rosterService,
                                PlayerService playerService,
                                LineupService lineupService,
                                GameManagementService gameManagementService,
                                WalkUpSongService walkUpSongService,
                                ManagerAccessService accessService,
                                TeamRosterEntryRepository rosterEntryRepository) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
        this.walkUpSongService = walkUpSongService;
        this.accessService = accessService;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    /** Adds one run to a weekly roster entry. */
    @PostMapping("/runs/add")
    public ManagerDashboardState addRun(@RequestBody ManagerActionRequest request,
                                        Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        requireGameInProgress(entry.getTeam().getGameWeek());
        rosterService.addRun(entry.getId());
        return buildState(entry.getTeam().getGameWeek(), "Run added.");
    }

    /** Removes one run, never allowing the total to fall below zero. */
    @PostMapping("/runs/remove")
    public ManagerDashboardState removeRun(@RequestBody ManagerActionRequest request,
                                           Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        requireGameInProgress(entry.getTeam().getGameWeek());
        rosterService.removeRun(entry.getId());
        return buildState(entry.getTeam().getGameWeek(), "Run removed.");
    }

    /** Moves a player one position earlier in the batting order. */
    @PostMapping("/lineup/up")
    public ManagerDashboardState moveUp(@RequestBody ManagerActionRequest request,
                                        Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        lineupService.moveUp(entry.getId());
        return buildState(entry.getTeam().getGameWeek(), "Batting order updated.");
    }

    /** Moves a player one position later in the batting order. */
    @PostMapping("/lineup/down")
    public ManagerDashboardState moveDown(@RequestBody ManagerActionRequest request,
                                          Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        lineupService.moveDown(entry.getId());
        return buildState(entry.getTeam().getGameWeek(), "Batting order updated.");
    }

    /** Removes a player from a weekly team roster. */
    @PostMapping("/roster/remove")
    public ManagerDashboardState removePlayer(@RequestBody ManagerActionRequest request,
                                              Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        GameWeek week = entry.getTeam().getGameWeek();
        lineupService.removeFromRoster(entry.getId());
        return buildState(week, "Player removed from roster.");
    }

    /** Adds a registered player to a weekly team roster. */
    @PostMapping("/roster/add")
    public ManagerDashboardState addPlayer(@RequestBody ManagerActionRequest request,
                                           Authentication authentication) {
        Long teamId = required(request.getTeamId(), "teamId");
        Long playerId = required(request.getPlayerId(), "playerId");
        Player currentPlayer = accessService.currentPlayer(authentication);
        Team team = accessService.requireTeamAccess(teamId, currentPlayer);
        validateRequestedWeek(request.getGameWeekId(), team.getGameWeek());

        lineupService.addPlayerToTeam(teamId, playerId);
        return buildState(team.getGameWeek(), "Player added to roster.");
    }

    /** Advances to the next batter for the currently batting team. */
    @PostMapping("/game/next-batter")
    public ManagerDashboardState nextBatter(@RequestBody ManagerActionRequest request,
                                            Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireLiveGameActionAccess(week, request.getTeamId(), authentication);
        gameManagementService.nextBatter(week);
        return buildState(week, "Advanced to next batter.");
    }

    /** Ends the current at-bat and switches to the other team. */
    @PostMapping("/game/end-at-bat")
    public ManagerDashboardState endAtBat(@RequestBody ManagerActionRequest request,
                                          Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireLiveGameActionAccess(week, request.getTeamId(), authentication);
        gameManagementService.endAtBat(week);
        return buildState(week, "At-bat ended. Switched teams.");
    }

    /**
     * Verifies access to the roster entry and protects against a stale/mismatched
     * game-week id being sent by an old browser tab.
     */
    private TeamRosterEntry requireRosterEntryAccess(ManagerActionRequest request,
                                                     Authentication authentication) {
        Long rosterEntryId = required(request.getRosterEntryId(), "rosterEntryId");
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));

        Player currentPlayer = accessService.currentPlayer(authentication);
        accessService.requireTeamAccess(entry.getTeam().getId(), currentPlayer);
        validateRequestedWeek(request.getGameWeekId(), entry.getTeam().getGameWeek());
        return entry;
    }

    /**
     * League Supervisors may operate on any live game. A normal Team Manager may
     * only operate while their assigned team is the current batting team.
     */
    private void requireLiveGameActionAccess(GameWeek week,
                                             Long requestedTeamId,
                                             Authentication authentication) {
        GameState state = requireGameInProgress(week);
        Player currentPlayer = accessService.currentPlayer(authentication);

        if (accessService.isLeagueSupervisor(currentPlayer)) {
            return;
        }

        Team managedTeam = requestedTeamId == null
                ? accessService.requirePrimaryManagedTeam(week, currentPlayer)
                : accessService.requireTeamAccess(requestedTeamId, currentPlayer);

        if (state.getCurrentBattingTeam() == null
                || !Objects.equals(state.getCurrentBattingTeam().getId(), managedTeam.getId())) {
            throw new AccessDeniedException("You can only use live at-bat controls while your team is batting.");
        }
    }

    private GameState requireGameInProgress(GameWeek week) {
        return gameManagementService.getGameState(week)
                .orElseThrow(() -> new IllegalStateException("Start the game before using live scoring controls."));
    }

    private GameWeek resolveWeek(Long gameWeekId) {
        return gameWeekId == null
                ? gameWeekService.getCurrentGameWeek()
                : gameWeekService.getGameWeek(gameWeekId);
    }

    private void validateRequestedWeek(Long requestedGameWeekId, GameWeek actualWeek) {
        if (requestedGameWeekId != null && !Objects.equals(requestedGameWeekId, actualWeek.getId())) {
            throw new IllegalArgumentException("The requested action does not belong to the selected game.");
        }
    }

    private Long required(Long value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }

    /** Builds the canonical client-side dashboard snapshot after a mutation. */
    private ManagerDashboardState buildState(GameWeek week, String message) {
        ManagerDashboardState response = new ManagerDashboardState();
        response.setMessage(message);
        response.setGameWeekId(week.getId());
        response.setGameStatus(week.getStatus() == null ? null : week.getStatus().name());

        GameState gameState = gameManagementService.getGameState(week).orElse(null);
        response.setGameInProgress(gameState != null);

        if (gameState != null) {
            response.setInning(gameState.getInning());
            if (gameState.getCurrentBattingTeam() != null) {
                response.setCurrentBattingTeamId(gameState.getCurrentBattingTeam().getId());
                response.setCurrentBattingTeamColor(gameState.getCurrentBattingTeam().getColor());
            }
            if (gameState.getCurrentBatterRosterEntry() != null) {
                response.setCurrentBatter(walkUpSongService.getWalkUpSongInfo(gameState));
            }
        }

        List<TeamScore> scores = gameManagementService.getScores(week);
        response.setScores(scores.stream()
                .map(score -> new ManagerDashboardState.ScoreState(
                        score.getTeamId(), score.getColor(), score.getName(), score.getRuns()))
                .toList());

        List<Team> teams = rosterService.getTeams(week);
        List<TeamRosterEntry> allEntries = rosterService.getRosterForWeek(week);
        Set<Long> assignedPlayerIds = new HashSet<>();

        List<ManagerDashboardState.TeamState> teamStates = teams.stream().map(team -> {
            ManagerDashboardState.TeamState teamState = new ManagerDashboardState.TeamState();
            teamState.setTeamId(team.getId());
            teamState.setColor(team.getColor());
            teamState.setName(team.getName());
            teamState.setManagerName(team.getManagerPlayer() == null ? null : displayName(team.getManagerPlayer()));

            List<ManagerDashboardState.RosterEntryState> roster = allEntries.stream()
                    .filter(entry -> Objects.equals(entry.getTeam().getId(), team.getId()))
                    .sorted(Comparator.comparingInt(TeamRosterEntry::getBattingOrder))
                    .map(entry -> {
                        assignedPlayerIds.add(entry.getPlayer().getId());
                        ManagerDashboardState.RosterEntryState row = new ManagerDashboardState.RosterEntryState();
                        row.setRosterEntryId(entry.getId());
                        row.setTeamId(team.getId());
                        row.setPlayerId(entry.getPlayer().getId());
                        row.setDisplayName(displayName(entry.getPlayer()));
                        row.setFullName(entry.getPlayer().getName());
                        row.setBattingOrder(entry.getBattingOrder());
                        row.setRunsScored(entry.getRunsScored());
                        row.setCurrentBatter(gameState != null
                                && gameState.getCurrentBatterRosterEntry() != null
                                && Objects.equals(gameState.getCurrentBatterRosterEntry().getId(), entry.getId()));
                        return row;
                    })
                    .toList();

            teamState.setRoster(roster);
            return teamState;
        }).toList();

        response.setTeams(teamStates);
        response.setAvailablePlayers(playerService.findActivePlayers().stream()
                .filter(player -> !assignedPlayerIds.contains(player.getId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(player -> new ManagerDashboardState.PlayerOption(player.getId(), displayName(player)))
                .toList());
        return response;
    }

    private String displayName(Player player) {
        return player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName();
    }
}
