package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.service.GameManagementService;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.LineupService;
import com.singleskickball.manager.service.PlayerService;
import com.singleskickball.manager.service.RosterService;
import com.singleskickball.manager.service.WalkUpSongService;
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
 * Manager-facing workflow for availability review, roster generation, lineup
 * management, live scoring, and game audio.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final PlayerService playerService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;
    private final WalkUpSongService walkUpSongService;

    public ManagerController(GameWeekService gameWeekService,
                             RosterService rosterService,
                             PlayerService playerService,
                             LineupService lineupService,
                             GameManagementService gameManagementService,
                             WalkUpSongService walkUpSongService) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
        this.walkUpSongService = walkUpSongService;
    }

    /**
     * Displays the main manager dashboard for the current game week.
     */
    @GetMapping
    public String dashboard(Model model) {
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

        return "manager/dashboard";
    }

    /**
     * Automatically generates weekly rosters. This should only be used before
     * the live game starts; the dashboard disables the button afterward.
     */
    @PostMapping("/generate-rosters")
    public String generateRosters(RedirectAttributes redirectAttributes) {
        var summary = rosterService.generateTwoTeamRoster(gameWeekService.getCurrentGameWeek());
        redirectAttributes.addFlashAttribute("message",
                "Rosters created: " + summary.getPlayersAssigned() + " players assigned.");
        return "redirect:/manager";
    }

    /**
     * Starts live game tracking and chooses the initial batter.
     */
    @PostMapping("/game/start")
    public String startGame(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.startGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game started.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }


    /**
     * Ends the current live game. Existing rosters, batting order, and runs are
     * preserved for season history. The week is marked FINAL and the app will
     * move on to the next scheduled non-final game.
     */
    @PostMapping("/game/end-game")
    public String endGame(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.endGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game ended. Runs and rosters were saved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /**
     * Restarts live game tracking for the current week using the existing
     * rosters. This is useful if a manager accidentally ends or starts the game.
     * It does not erase run totals.
     */
    @PostMapping("/game/restart")
    public String restartGame(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.restartGame(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "Game restarted. Existing rosters and runs were preserved.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /**
     * Non-AJAX fallback for advancing the batting order.
     */
    @PostMapping("/game/next-batter")
    public String nextBatter(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.nextBatter(gameWeekService.getCurrentGameWeek());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /**
     * AJAX endpoint used by the manager dashboard when the Next Batter button is
     * clicked. It advances the batter and returns audio metadata for the new
     * batter so the browser can immediately play intro -> walk-up song.
     */
    @PostMapping("/game/next-batter-info")
    @ResponseBody
    public WalkUpSongInfo nextBatterInfo() {
        GameState state = gameManagementService.nextBatter(gameWeekService.getCurrentGameWeek());
        if (state.getCurrentBatterRosterEntry() == null) {
            throw new IllegalStateException("No current batter is available.");
        }
        return walkUpSongService.getWalkUpSongInfo(state);
    }

    /**
     * Ends the current team's at-bat and switches to the other team.
     */
    @PostMapping("/game/end-at-bat")
    public String endAtBat(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.endAtBat(gameWeekService.getCurrentGameWeek());
            redirectAttributes.addFlashAttribute("message", "At-bat ended. Switched teams.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /** Adds one run to the selected roster entry/player. */
    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId) {
        rosterService.addRun(rosterEntryId);
        return "redirect:/manager";
    }

    /** Moves a batter one slot earlier in the lineup. */
    @PostMapping("/lineup/{rosterEntryId}/up")
    public String movePlayerUp(@PathVariable Long rosterEntryId) {
        lineupService.moveUp(rosterEntryId);
        return "redirect:/manager";
    }

    /** Moves a batter one slot later in the lineup. */
    @PostMapping("/lineup/{rosterEntryId}/down")
    public String movePlayerDown(@PathVariable Long rosterEntryId) {
        lineupService.moveDown(rosterEntryId);
        return "redirect:/manager";
    }

    /** Removes a player from a weekly team roster. */
    @PostMapping("/roster/{rosterEntryId}/remove")
    public String removeFromRoster(@PathVariable Long rosterEntryId) {
        lineupService.removeFromRoster(rosterEntryId);
        return "redirect:/manager";
    }

    /** Manually adds an unassigned registered player to a weekly team. */
    @PostMapping("/teams/{teamId}/add-player")
    public String addPlayerToTeam(@PathVariable Long teamId,
                                  @RequestParam Long playerId,
                                  RedirectAttributes redirectAttributes) {
        try {
            lineupService.addPlayerToTeam(teamId, playerId);
            redirectAttributes.addFlashAttribute("message", "Player added to roster.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

    /**
     * Returns current batter audio information when the game is running.
     */
    private WalkUpSongInfo getCurrentWalkUpSongInfo(GameState gameState) {
        if (gameState == null || gameState.getCurrentBatterRosterEntry() == null) {
            return null;
        }
        return walkUpSongService.getWalkUpSongInfo(gameState);
    }
}
