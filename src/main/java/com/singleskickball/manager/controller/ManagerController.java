package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Manager-facing workflow for availability review, roster generation, lineup
 * management, and live scoring.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final PlayerService playerService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;

    public ManagerController(GameWeekService gameWeekService,
                             RosterService rosterService,
                             PlayerService playerService,
                             LineupService lineupService,
                             GameManagementService gameManagementService) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
    }

    @GetMapping
    public String dashboard(Model model) {
        GameWeek week = gameWeekService.getCurrentGameWeek();
        model.addAttribute("week", week);
        model.addAttribute("availability", gameWeekService.getAvailabilityForWeek(week));
        model.addAttribute("teams", rosterService.getTeams(week));
        model.addAttribute("rosterEntries", rosterService.getRosterForWeek(week));
        model.addAttribute("players", playerService.findActivePlayers());
        model.addAttribute("gameState", gameManagementService.getGameState(week).orElse(null));
        model.addAttribute("scores", gameManagementService.getScores(week));
        return "manager/dashboard";
    }

    @PostMapping("/generate-rosters")
    public String generateRosters(RedirectAttributes redirectAttributes) {
        var summary = rosterService.generateTwoTeamRoster(gameWeekService.getCurrentGameWeek());
        redirectAttributes.addFlashAttribute("message",
                "Rosters created: " + summary.getPlayersAssigned() + " players assigned.");
        return "redirect:/manager";
    }

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

    @PostMapping("/game/next-batter")
    public String nextBatter(RedirectAttributes redirectAttributes) {
        try {
            gameManagementService.nextBatter(gameWeekService.getCurrentGameWeek());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager";
    }

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

    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId) {
        rosterService.addRun(rosterEntryId);
        return "redirect:/manager";
    }

    @PostMapping("/lineup/{rosterEntryId}/up")
    public String movePlayerUp(@PathVariable Long rosterEntryId) {
        lineupService.moveUp(rosterEntryId);
        return "redirect:/manager";
    }

    @PostMapping("/lineup/{rosterEntryId}/down")
    public String movePlayerDown(@PathVariable Long rosterEntryId) {
        lineupService.moveDown(rosterEntryId);
        return "redirect:/manager";
    }

    @PostMapping("/roster/{rosterEntryId}/remove")
    public String removeFromRoster(@PathVariable Long rosterEntryId) {
        lineupService.removeFromRoster(rosterEntryId);
        return "redirect:/manager";
    }

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
}
