package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.AudioTargetRequest;
import com.singleskickball.manager.dto.AudioTargetState;
import com.singleskickball.manager.dto.ManagerActionRequest;
import com.singleskickball.manager.dto.ManagerDashboardState;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import com.singleskickball.manager.service.BetweenAtBatSongService;
import com.singleskickball.manager.service.GameManagementService;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.LineupService;
import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.ManagerDashboardStateService;
import com.singleskickball.manager.service.ManagerLiveUpdateService;
import com.singleskickball.manager.service.RosterService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * JSON API for high-frequency manager actions.
 *
 * Each action now has two outputs:
 *  1. the normal JSON response returned to the device that performed the action;
 *  2. the same authoritative snapshot broadcast through Server-Sent Events to
 *     every other manager dashboard watching this game.
 */
@RestController
@RequestMapping("/manager/api")
public class ManagerApiController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;
    private final ManagerAccessService accessService;
    private final TeamRosterEntryRepository rosterEntryRepository;
    private final ManagerDashboardStateService dashboardStateService;
    private final ManagerLiveUpdateService liveUpdateService;
    private final BetweenAtBatSongService betweenAtBatSongService;

    public ManagerApiController(GameWeekService gameWeekService,
                                RosterService rosterService,
                                LineupService lineupService,
                                GameManagementService gameManagementService,
                                ManagerAccessService accessService,
                                TeamRosterEntryRepository rosterEntryRepository,
                                ManagerDashboardStateService dashboardStateService,
                                ManagerLiveUpdateService liveUpdateService,
                                BetweenAtBatSongService betweenAtBatSongService) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
        this.accessService = accessService;
        this.rosterEntryRepository = rosterEntryRepository;
        this.dashboardStateService = dashboardStateService;
        this.liveUpdateService = liveUpdateService;
        this.betweenAtBatSongService = betweenAtBatSongService;
    }

    /** Adds one run to a weekly roster entry. */
    @PostMapping("/runs/add")
    public ManagerDashboardState addRun(@RequestBody ManagerActionRequest request,
                                        Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        requireGameInProgress(entry.getTeam().getGameWeek());
        rosterService.addRun(entry.getId());
        return buildPublishAndReturn(entry.getTeam().getGameWeek(), "Run added.");
    }

    /** Removes one run, never allowing the total to fall below zero. */
    @PostMapping("/runs/remove")
    public ManagerDashboardState removeRun(@RequestBody ManagerActionRequest request,
                                           Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        requireGameInProgress(entry.getTeam().getGameWeek());
        rosterService.removeRun(entry.getId());
        return buildPublishAndReturn(entry.getTeam().getGameWeek(), "Run removed.");
    }

    /** Moves a player one position earlier in the batting order. */
    @PostMapping("/lineup/up")
    public ManagerDashboardState moveUp(@RequestBody ManagerActionRequest request,
                                        Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        lineupService.moveUp(entry.getId());
        return buildPublishAndReturn(entry.getTeam().getGameWeek(), "Batting order updated.");
    }

    /** Moves a player one position later in the batting order. */
    @PostMapping("/lineup/down")
    public ManagerDashboardState moveDown(@RequestBody ManagerActionRequest request,
                                          Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        lineupService.moveDown(entry.getId());
        return buildPublishAndReturn(entry.getTeam().getGameWeek(), "Batting order updated.");
    }

    /** Removes a player from a weekly team roster. */
    @PostMapping("/roster/remove")
    public ManagerDashboardState removePlayer(@RequestBody ManagerActionRequest request,
                                              Authentication authentication) {
        TeamRosterEntry entry = requireRosterEntryAccess(request, authentication);
        GameWeek week = entry.getTeam().getGameWeek();
        lineupService.removeFromRoster(entry.getId());
        return buildPublishAndReturn(week, "Player removed from roster.");
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
        return buildPublishAndReturn(team.getGameWeek(), "Player added to roster.");
    }

    /**
     * Starts live tracking for the selected game.
     *
     * <p>Either the League Supervisor or any Team Manager assigned to this game
     * may start it. Publishing the resulting snapshot immediately causes every
     * connected manager dashboard to transition into the live-game controls.</p>
     */
    @PostMapping("/game/start")
    public ManagerDashboardState startGame(@RequestBody ManagerActionRequest request,
                                           Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());

        // Validates that the caller is the League Supervisor or manages one of
        // this game's teams. The helper already handles both roles.
        requireManagerAccess(week, authentication);

        if (gameManagementService.getGameState(week).isPresent()) {
            throw new IllegalStateException("This game has already been started.");
        }

        gameManagementService.startGame(week);
        return buildPublishAndReturn(week, "Game started.");
    }

    /** Advances to the next batter for the currently batting team. */
    @PostMapping("/game/next-batter")
    public ManagerDashboardState nextBatter(@RequestBody ManagerActionRequest request,
                                            Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireLiveGameActionAccess(week, request.getTeamId(), authentication);

        // Stop break music before changing the highlighted batter or starting
        // the intro/walk-up sequence.
        liveUpdateService.stopBetweenAtBatAudio(week.getId());

        gameManagementService.nextBatter(week);
        ManagerDashboardState state = buildPublishAndReturn(week, "Advanced to next batter.");
        liveUpdateService.publishAudioCommandIfTargeted(week.getId(), state.getCurrentBatter());
        return state;
    }

    /**
     * Moves back one batter and plays that batter's audio using the same routing
     * rules as Next Batter.
     */
    @PostMapping("/game/previous-batter")
    public ManagerDashboardState previousBatter(@RequestBody ManagerActionRequest request,
                                                Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireLiveGameActionAccess(week, request.getTeamId(), authentication);

        // Any field-change music must stop before the corrected batter audio.
        liveUpdateService.stopBetweenAtBatAudio(week.getId());

        gameManagementService.previousBatter(week);
        ManagerDashboardState state =
                buildPublishAndReturn(week, "Moved to previous batter.");

        liveUpdateService.publishAudioCommandIfTargeted(
                week.getId(),
                state.getCurrentBatter());

        return state;
    }

    /** Ends the current at-bat and switches to the other team. */
    @PostMapping("/game/end-at-bat")
    public ManagerDashboardState endAtBat(@RequestBody ManagerActionRequest request,
                                          Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireLiveGameActionAccess(week, request.getTeamId(), authentication);

        gameManagementService.endAtBat(week);
        ManagerDashboardState state =
                buildPublishAndReturn(week, "At-bat ended. Switched teams.");

        // Play one alphabetical break song. Dedicated audio routing wins; when
        // none is selected, the device that clicked End At-Bat plays it.
        String songUrl = betweenAtBatSongService.nextSongUrl(week.getId());
        liveUpdateService.publishBetweenAtBatSong(
                week.getId(),
                request.getDeviceId(),
                songUrl);

        return state;
    }



    /**
     * Requests the next alphabetical between-at-bat MP3 after the current song
     * finishes on the assigned audio device.
     */
    @PostMapping("/audio/between-at-bat/next")
    public ManagerDashboardState nextBetweenAtBatSong(
            @RequestBody ManagerActionRequest request,
            Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireManagerAccess(week, authentication);
        requireGameInProgress(week);

        String songUrl = betweenAtBatSongService.nextSongUrl(week.getId());
        liveUpdateService.continueBetweenAtBatSong(
                week.getId(),
                request.getDeviceId(),
                songUrl);

        // No game state changed, but returning the normal snapshot keeps the API
        // response format consistent for the JavaScript client.
        return dashboardStateService.buildState(
                week,
                "Between-at-bat playlist continued.");
    }

    /**
     * Routes the current batter's audio according to the game's active audio mode.
     *
     * <p>When a dedicated audio target has been selected, this endpoint sends an
     * SSE audio command only to that target device. The browser that clicked the
     * button does not play locally unless it is the selected target. When no
     * dedicated target exists, the client keeps the normal local-play behavior.</p>
     */
    @PostMapping("/audio/play-current")
    public ManagerDashboardState playCurrentBatterAudio(@RequestBody ManagerActionRequest request,
                                                        Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireManagerAccess(week, authentication);
        requireGameInProgress(week);

        ManagerDashboardState state =
                dashboardStateService.buildState(week, "Current batter audio requested.");

        if (state.getCurrentBatter() == null) {
            throw new IllegalStateException("There is no current batter.");
        }

        liveUpdateService.publishAudioCommandIfTargeted(
                week.getId(), state.getCurrentBatter());

        return state;
    }

    /** Claims all walk-up audio for the requesting manager device. */
    @PostMapping("/audio-target/claim")
    public AudioTargetState claimAudioTarget(@RequestBody AudioTargetRequest request,
                                             Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        Player currentPlayer = requireManagerAccess(week, authentication);
        String deviceId = requiredText(request.getDeviceId(), "deviceId");
        String managerName = currentPlayer.getNickname() != null && !currentPlayer.getNickname().isBlank()
                ? currentPlayer.getNickname()
                : currentPlayer.getName();
        return liveUpdateService.claimAudioTarget(week.getId(), deviceId, managerName);
    }

    /** Returns audio routing to the normal current-at-bat manager device. */
    @PostMapping("/audio-target/release")
    public AudioTargetState releaseAudioTarget(@RequestBody AudioTargetRequest request,
                                               Authentication authentication) {
        GameWeek week = resolveWeek(request.getGameWeekId());
        requireManagerAccess(week, authentication);
        return liveUpdateService.releaseAudioTarget(
                week.getId(), requiredText(request.getDeviceId(), "deviceId"));
    }

    /** Ensures the caller is either the League Supervisor or a manager in this game. */
    private Player requireManagerAccess(GameWeek week, Authentication authentication) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        if (!accessService.isLeagueSupervisor(currentPlayer)) {
            accessService.requirePrimaryManagedTeam(week, currentPlayer);
        }
        return currentPlayer;
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    /**
     * Builds the snapshot once, broadcasts it, and returns that same object to
     * the initiating browser. This prevents the local and remote views from
     * ever receiving subtly different data.
     */
    private ManagerDashboardState buildPublishAndReturn(GameWeek week, String message) {
        ManagerDashboardState state = dashboardStateService.buildState(week, message);
        liveUpdateService.publish(state);
        return state;
    }

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
     * League Supervisors may operate on any live game. A Team Manager may use
     * at-bat controls only while their assigned team is batting.
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
            throw new AccessDeniedException(
                    "You can only use live at-bat controls while your team is batting.");
        }
    }

    private GameState requireGameInProgress(GameWeek week) {
        return gameManagementService.getGameState(week)
                .orElseThrow(() -> new IllegalStateException(
                        "Start the game before using live scoring controls."));
    }

    private GameWeek resolveWeek(Long gameWeekId) {
        return gameWeekId == null
                ? gameWeekService.getCurrentGameWeek()
                : gameWeekService.getGameWeek(gameWeekId);
    }

    private void validateRequestedWeek(Long requestedGameWeekId, GameWeek actualWeek) {
        if (requestedGameWeekId != null
                && !Objects.equals(requestedGameWeekId, actualWeek.getId())) {
            throw new IllegalArgumentException(
                    "The requested action does not belong to the selected game.");
        }
    }

    private Long required(Long value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }
}
