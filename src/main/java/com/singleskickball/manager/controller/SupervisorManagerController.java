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

    /** Shows the full supervisor dashboard for the current game week. */
    @GetMapping
    public String dashboard(Model model, Authentication authentication) {
        Player currentPlayer = accessService.requireLeagueSupervisor(authentication);

        GameWeek week = gameWeekService.getCurrentGameWeek();
        GameState gameState = gameManagementService.getGameState(week).orElse(null);

        model.addAttribute("week", week);
        model.addAttribute("availability", gameWeekService.getAvailabilityForWeek(week));
        model.addAttribute("teams", rosterService.getTeams(week));
        model.addAttribute("rosterEntries", rosterService.getRosterForWeek(week));
        model.addAttribute("players", playerService.findActivePlayers());
        model.addAttribute("gameState", gameState);
        model.addAttribute("scores", gameManagementService.getScores(week));
        model.addAttribute("currentWalkUpSong", getCurrentWalkUpSongInfo(gameState));

        // Show the Team Manager shortcut only when the logged-in supervisor is
        // actually assigned to a team for this game. A League Supervisor can
        // administer all teams from this page even if they are not serving as
        // a Team Manager this week.
        model.addAttribute("hasTeamManagerView", !accessService.manageableTeams(week, currentPlayer).isEmpty());

        return "manager/supervisor-dashboard";
    }

    /** Automatically creates balanced rosters for the current game. */
    @PostMapping("/generate-rosters")
    public String generateRosters(Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        var summary = rosterService.generateTwoTeamRoster(gameWeekService.getCurrentGameWeek());
        redirectAttributes.addFlashAttribute("message",
                "Rosters created: " + summary.getPlayersAssigned() + " players assigned.");
        return "redirect:/manager/supervisor";
    }

    /** Starts live game tracking. */
    @PostMapping("/game/start")
    public String startGame(Authentication authentication,
                            RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            gameManagementService.startGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game started.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    /** Ends the game and marks the game week final. */
    @PostMapping("/game/end-game")
    public String endGame(Authentication authentication,
                          RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            gameManagementService.endGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game ended. Runs and rosters were saved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    /** Restarts live tracking without deleting rosters or runs. */
    @PostMapping("/game/restart")
    public String restartGame(Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            gameManagementService.restartGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game restarted. Existing rosters and runs were preserved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    /** Non-AJAX fallback for advancing the current batter. */
    @PostMapping("/game/next-batter")
    public String nextBatter(Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            gameManagementService.nextBatter(gameWeekService.getCurrentGameWeek());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    /** AJAX endpoint used by the supervisor dashboard's Next Batter button. */
    @PostMapping("/game/next-batter-info")
    @ResponseBody
    public WalkUpSongInfo nextBatterInfo(Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        GameState state = gameManagementService.nextBatter(gameWeekService.getCurrentGameWeek());
        if (state.getCurrentBatterRosterEntry() == null) {
            throw new IllegalStateException("No current batter is available.");
        }
        return walkUpSongService.getWalkUpSongInfo(state);
    }

    /** Switches the current at-bat to the next team. */
    @PostMapping("/game/end-at-bat")
    public String endAtBat(Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            gameManagementService.endAtBat(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "At-bat ended. Switched teams.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    /** Adds one run to a player. */
    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId,
                         Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        rosterService.addRun(rosterEntryId);
        return "redirect:/manager/supervisor";
    }

    /** Removes one run from a player, never below zero. */
    @PostMapping("/runs/{rosterEntryId}/remove")
    public String removeRun(@PathVariable Long rosterEntryId,
                            Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        rosterService.removeRun(rosterEntryId);
        return "redirect:/manager/supervisor";
    }

    @PostMapping("/lineup/{rosterEntryId}/up")
    public String movePlayerUp(@PathVariable Long rosterEntryId,
                               Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.moveUp(rosterEntryId);
        return "redirect:/manager/supervisor";
    }

    @PostMapping("/lineup/{rosterEntryId}/down")
    public String movePlayerDown(@PathVariable Long rosterEntryId,
                                 Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.moveDown(rosterEntryId);
        return "redirect:/manager/supervisor";
    }

    @PostMapping("/roster/{rosterEntryId}/remove")
    public String removeFromRoster(@PathVariable Long rosterEntryId,
                                   Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        lineupService.removeFromRoster(rosterEntryId);
        return "redirect:/manager/supervisor";
    }

    @PostMapping("/teams/{teamId}/add-player")
    public String addPlayerToTeam(@PathVariable Long teamId,
                                  @RequestParam Long playerId,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);
        try {
            lineupService.addPlayerToTeam(teamId, playerId);
            redirectAttributes.addFlashAttribute("message", "Player added to roster.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/supervisor";
    }

    private WalkUpSongInfo getCurrentWalkUpSongInfo(GameState gameState) {
        if (gameState == null || gameState.getCurrentBatterRosterEntry() == null) {
            return null;
        }
        return walkUpSongService.getWalkUpSongInfo(gameState);
    }
}
