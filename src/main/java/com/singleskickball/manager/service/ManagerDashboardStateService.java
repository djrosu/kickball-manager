package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.ManagerDashboardState;
import com.singleskickball.manager.dto.TeamScore;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the authoritative JSON snapshot used by every manager dashboard.
 *
 * Keeping snapshot creation in one service is important for live sync: the
 * HTTP action response and the Server-Sent Event must contain exactly the same
 * representation of the game. The transaction keeps lazy JPA relationships
 * available while the DTO is assembled, but no entity is exposed to the
 * browser.
 */
@Service
public class ManagerDashboardStateService {

    private final RosterService rosterService;
    private final PlayerService playerService;
    private final GameManagementService gameManagementService;
    private final WalkUpSongService walkUpSongService;

    public ManagerDashboardStateService(RosterService rosterService,
                                        PlayerService playerService,
                                        GameManagementService gameManagementService,
                                        WalkUpSongService walkUpSongService) {
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.gameManagementService = gameManagementService;
        this.walkUpSongService = walkUpSongService;
    }

    /**
     * Creates a complete dashboard state after a manager action or when a live
     * browser first connects. The message is shown only to the browser that
     * initiated an action; remote live-update clients generally ignore it.
     */
    @Transactional(readOnly = true)
    public ManagerDashboardState buildState(GameWeek week, String message) {
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
            teamState.setManagerName(team.getManagerPlayer() == null
                    ? null
                    : displayName(team.getManagerPlayer()));

            List<ManagerDashboardState.RosterEntryState> roster = allEntries.stream()
                    .filter(entry -> Objects.equals(entry.getTeam().getId(), team.getId()))
                    .sorted(Comparator.comparingInt(TeamRosterEntry::getBattingOrder))
                    .map(entry -> {
                        assignedPlayerIds.add(entry.getPlayer().getId());

                        ManagerDashboardState.RosterEntryState row =
                                new ManagerDashboardState.RosterEntryState();
                        row.setRosterEntryId(entry.getId());
                        row.setTeamId(team.getId());
                        row.setPlayerId(entry.getPlayer().getId());
                        row.setDisplayName(displayName(entry.getPlayer()));
                        row.setFullName(entry.getPlayer().getName());
                        row.setBattingOrder(entry.getBattingOrder());
                        row.setRunsScored(entry.getRunsScored());
                        row.setCurrentBatter(gameState != null
                                && gameState.getCurrentBatterRosterEntry() != null
                                && Objects.equals(
                                        gameState.getCurrentBatterRosterEntry().getId(),
                                        entry.getId()));
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
                .map(player -> new ManagerDashboardState.PlayerOption(
                        player.getId(), displayName(player)))
                .toList());

        return response;
    }

    private String displayName(Player player) {
        return player.getNickname() != null && !player.getNickname().isBlank()
                ? player.getNickname()
                : player.getName();
    }
}
