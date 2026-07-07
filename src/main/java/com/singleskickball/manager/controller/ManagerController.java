package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Manager-facing workflow for availability review, roster generation, and scoring.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;

    public ManagerController(GameWeekService gameWeekService, RosterService rosterService) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
    }

    @GetMapping
    public String dashboard(Model model) {
        GameWeek week = gameWeekService.getCurrentGameWeek();
        model.addAttribute("week", week);
        model.addAttribute("availability", gameWeekService.getAvailabilityForWeek(week));
        model.addAttribute("teams", rosterService.getTeams(week));
        model.addAttribute("rosterEntries", rosterService.getRosterForWeek(week));
        return "manager/dashboard";
    }

    @PostMapping("/generate-rosters")
    public String generateRosters() {
        rosterService.generateTwoTeamRoster(gameWeekService.getCurrentGameWeek());
        return "redirect:/manager";
    }

    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId) {
        rosterService.addRun(rosterEntryId);
        return "redirect:/manager";
    }
}
