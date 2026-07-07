package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Player-facing home page.
 *
 * Players use this page to:
 * - mark weekly availability
 * - select a preferred teammate
 * - update basic profile information
 * - view league run leaders
 */
@Controller
public class HomeController {

    private final PlayerService playerService;
    private final GameWeekService gameWeekService;
    private final RosterService rosterService;

    public HomeController(PlayerService playerService,
                          GameWeekService gameWeekService,
                          RosterService rosterService) {
        this.playerService = playerService;
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
    }

    @GetMapping("/")
    public String home(Model model, Authentication authentication) {
        Player player = playerService.getCurrentPlayer(authentication);
        GameWeek week = gameWeekService.getCurrentGameWeek();

        model.addAttribute("player", player);
        model.addAttribute("week", week);
        model.addAttribute("players", playerService.findActivePlayers());
        model.addAttribute("leaders", rosterService.getTopRunLeaders());

        // These values let the page show what the player already selected.
        model.addAttribute("currentAvailability", gameWeekService.getAvailabilityStatus(week, player).orElse(null));
        model.addAttribute("preferredPlayerId", gameWeekService.getPreferredPlayerId(week, player).orElse(null));

        return "home";
    }

    @PostMapping("/availability")
    public String updateAvailability(@RequestParam AvailabilityStatus status,
                                     Authentication authentication) {
        Player player = playerService.getCurrentPlayer(authentication);
        gameWeekService.setAvailability(gameWeekService.getCurrentGameWeek(), player, status);
        return "redirect:/";
    }

    @PostMapping("/preference")
    public String updatePreference(@RequestParam Long preferredPlayerId,
                                   Authentication authentication) {
        Player player = playerService.getCurrentPlayer(authentication);
        gameWeekService.setPreference(gameWeekService.getCurrentGameWeek(), player, preferredPlayerId);
        return "redirect:/";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String nickname,
                                @RequestParam(required = false) String walkUpSongArtist,
                                @RequestParam(required = false) String walkUpSongTitle,
                                Authentication authentication) {
        Player player = playerService.getCurrentPlayer(authentication);
        playerService.updateProfile(player, nickname, walkUpSongArtist, walkUpSongTitle);
        return "redirect:/";
    }
}