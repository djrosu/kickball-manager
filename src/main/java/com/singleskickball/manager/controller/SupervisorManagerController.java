package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.service.GameManagementService;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.LineupService;
import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.PlayerService;
import com.singleskickball.manager.service.RosterService;
import com.singleskickball.manager.service.WalkUpSongService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * League Supervisor dashboard and actions.
 *
 * A League Supervisor can see and operate on every team in the selected game.
 * This controller intentionally keeps the same capabilities the old manager
 * dashboard had, while normal Team Managers now use TeamManagerController.
 */
@Controller
@RequestMapping("/manager/supervisor")
public class SupervisorManagerController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final PlayerService playerService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;
    private final WalkUpSongService walkUpSongService;
    private final ManagerAccessService accessService;

    public SupervisorManagerController(GameWeekService gameWeekService,
                                       RosterService rosterService,
                                       PlayerService playerService,
                                       LineupService lineupService,
                                       GameManagementService gameManagementService,
                                       WalkUpSongService walkUpSongService,
                                       ManagerAccessService accessService) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
        this.walkUpSongService = walkUpSongService;
        this.accessService = accessService;
    }

    /**
     * Shows the full supervisor dashboard.
     *
     * By default the dashboard uses the app's current game week. The optional
     * gameWeekId parameter lets the League Supervisor intentionally manage a
     * prior or future game from the Game Administration section. This avoids
     * database edits when a game is accidentally ended or the wrong week needs
     * to be reviewed.
     */
    @GetMapping
    public String dashboard(@RequestParam(required = false) Long gameWeekId,
                            Model model,
                            Authentication authentication) {
        Player supervisor = accessService.requireLeagueSupervisor(authentication);
        Set<Long> supervisorManagedGameWeekIds = accessService.managedGameWeekIds(supervisor);

        GameWeek week = resolveWeek(gameWeekId);
        GameState gameState = gameManagementService.getGameState(week).orElse(null);

        model.addAttribute("player", supervisor);
        model.addAttribute("week", week);
        model.addAttribute("selectedGameWeekId", week.getId());
        model.addAttribute("supervisorManagedGameWeekIds", supervisorManagedGameWeekIds);
        model.addAttribute("allGameWeeks", orderGameWeeksForSupervisor(supervisorManagedGameWeekIds));
        model.addAttribute("availability", gameWeekService.getAvailabilityForWeek(week));
        model.addAttribute("teams", rosterService.getTeams(week));
        model.addAttribute("rosterEntries", rosterService.getRosterForWeek(week));
        model.addAttribute("players", playerService.findActivePlayers());
        model.addAttribute("gameState", gameState);
        model.addAttribute("scores", gameManagementService.getScores(week));
        model.addAttribute("currentWalkUpSong", getCurrentWalkUpSongInfo(gameState));

        return "manager/supervisor-dashboard";
    }

    /** Automatically creates balanced rosters for the current game. */
    @PostMapping("/generate-rosters")
    public String generateRosters(@RequestParam(required = false) Long gameWeekId,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        var summary = rosterService.generateTwoTeamRoster(week);
        redirectAttributes.addFlashAttribute("message",
                "Rosters created: " + summary.getPlayersAssigned() + " players assigned.");
        return redirectToWeek(week);
    }

    /** Starts live game tracking. */
    @PostMapping("/game/start")
    public String startGame(@RequestParam(required = false) Long gameWeekId,
                            Authentication authentication,
                            RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.startGame(week);
            redirectAttributes.addFlashAttribute("message", "Game started.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Ends the game and marks the game week final. */
    @PostMapping("/game/end-game")
    public String endGame(@RequestParam(required = false) Long gameWeekId,
                          Authentication authentication,
                          RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.endGame(week);
            redirectAttributes.addFlashAttribute("message", "Game ended. Runs and rosters were saved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Restarts live tracking without deleting rosters or runs. */
    @PostMapping("/game/restart")
    public String restartGame(@RequestParam(required = false) Long gameWeekId,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.restartGame(week);
            redirectAttributes.addFlashAttribute("message", "Game restarted. Existing rosters and runs were preserved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Non-AJAX fallback for advancing the current batter. */
    @PostMapping("/game/next-batter")
    public String nextBatter(@RequestParam(required = false) Long gameWeekId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.nextBatter(week);
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** AJAX endpoint used by the supervisor dashboard's Next Batter button. */
    @PostMapping("/game/next-batter-info")
    @ResponseBody
    public WalkUpSongInfo nextBatterInfo(@RequestParam(required = false) Long gameWeekId,
                                         Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        GameState state = gameManagementService.nextBatter(resolveWeek(gameWeekId));
        if (state.getCurrentBatterRosterEntry() == null) {
            throw new IllegalStateException("No current batter is available.");
        }
        return walkUpSongService.getWalkUpSongInfo(state);
    }

    /** Switches the current at-bat to the next team. */
    @PostMapping("/game/end-at-bat")
    public String endAtBat(@RequestParam(required = false) Long gameWeekId,
                           Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.endAtBat(week);
            redirectAttributes.addFlashAttribute("message", "At-bat ended. Switched teams.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Adds one run to a player. */
    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId,
                         @RequestParam(required = false) Long gameWeekId,
                         Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        rosterService.addRun(rosterEntryId);
        return redirectToSelectedWeek(gameWeekId);
    }

    /** Removes one run from a player, never below zero. */
    @PostMapping("/runs/{rosterEntryId}/remove")
    public String removeRun(@PathVariable Long rosterEntryId,
                            @RequestParam(required = false) Long gameWeekId,
                            Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        rosterService.removeRun(rosterEntryId);
        return redirectToSelectedWeek(gameWeekId);
    }

    @PostMapping("/lineup/{rosterEntryId}/up")
    public String movePlayerUp(@PathVariable Long rosterEntryId,
                               @RequestParam(required = false) Long gameWeekId,
                               Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.moveUp(rosterEntryId);
        return redirectToSelectedWeek(gameWeekId);
    }

    @PostMapping("/lineup/{rosterEntryId}/down")
    public String movePlayerDown(@PathVariable Long rosterEntryId,
                                 @RequestParam(required = false) Long gameWeekId,
                                 Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.moveDown(rosterEntryId);
        return redirectToSelectedWeek(gameWeekId);
    }

    @PostMapping("/roster/{rosterEntryId}/remove")
    public String removeFromRoster(@PathVariable Long rosterEntryId,
                                   @RequestParam(required = false) Long gameWeekId,
                                   Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.removeFromRoster(rosterEntryId);
        return redirectToSelectedWeek(gameWeekId);
    }

    @PostMapping("/teams/{teamId}/add-player")
    public String addPlayerToTeam(@PathVariable Long teamId,
                                  @RequestParam Long playerId,
                                  @RequestParam(required = false) Long gameWeekId,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            lineupService.addPlayerToTeam(teamId, playerId);
            redirectAttributes.addFlashAttribute("message", "Player added to roster.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToSelectedWeek(gameWeekId);
    }

    /**
     * Supervisor action for resuming any scheduled game week from the Game
     * Administration section. Existing rosters and run totals are preserved.
     */
    @PostMapping("/game-weeks/{gameWeekId}/resume")
    public String resumeGameWeek(@PathVariable Long gameWeekId,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.resumeGame(week);
            redirectAttributes.addFlashAttribute("message", "Game resumed for " + week.getGameDate() + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Restarts a selected game week without deleting rosters or run totals. */
    @PostMapping("/game-weeks/{gameWeekId}/restart")
    public String restartGameWeek(@PathVariable Long gameWeekId,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.restartGame(week);
            redirectAttributes.addFlashAttribute("message", "Game restarted for " + week.getGameDate() + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Ends a selected game week and marks it final. */
    @PostMapping("/game-weeks/{gameWeekId}/end")
    public String endGameWeek(@PathVariable Long gameWeekId,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.endGame(week);
            redirectAttributes.addFlashAttribute("message", "Game ended for " + week.getGameDate() + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    /** Reopens a selected game week for availability without deleting data. */
    @PostMapping("/game-weeks/{gameWeekId}/open-availability")
    public String reopenGameWeek(@PathVariable Long gameWeekId,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        GameWeek week = resolveWeek(gameWeekId);
        try {
            gameManagementService.reopenForAvailability(week);
            redirectAttributes.addFlashAttribute("message", "Game reopened for availability for " + week.getGameDate() + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return redirectToWeek(week);
    }

    private WalkUpSongInfo getCurrentWalkUpSongInfo(GameState gameState) {
        if (gameState == null || gameState.getCurrentBatterRosterEntry() == null) {
            return null;
        }
        return walkUpSongService.getWalkUpSongInfo(gameState);
    }

    /** Resolves a requested game week or falls back to the normal current week. */
    private GameWeek resolveWeek(Long gameWeekId) {
        return gameWeekId == null ? gameWeekService.getCurrentGameWeek() : gameWeekService.getGameWeek(gameWeekId);
    }

    /**
     * Sorts game weeks for the League Supervisor.
     *
     * A League Supervisor may also be assigned as the manager for one team.
     * Those games should appear first so the supervisor can keep their own game
     * expanded/focused while still having access to every other game.
     */
    private List<GameWeek> orderGameWeeksForSupervisor(Set<Long> supervisorManagedGameWeekIds) {
        List<GameWeek> weeks = new ArrayList<>(gameWeekService.getAllGameWeeks());
        weeks.sort(Comparator
                .comparing((GameWeek gameWeek) -> !supervisorManagedGameWeekIds.contains(gameWeek.getId()))
                .thenComparing(GameWeek::getGameDate)
                .thenComparing(GameWeek::getGameTime)
                .thenComparing(GameWeek::getId));
        return weeks;
    }

    /** Keeps the supervisor on the selected game after an action completes. */
    private String redirectToWeek(GameWeek week) {
        return "redirect:/manager/supervisor?gameWeekId=" + week.getId();
    }

    /** Redirect helper for actions where the week id is optional. */
    private String redirectToSelectedWeek(Long gameWeekId) {
        return gameWeekId == null ? "redirect:/manager/supervisor" : "redirect:/manager/supervisor?gameWeekId=" + gameWeekId;
    }
}
