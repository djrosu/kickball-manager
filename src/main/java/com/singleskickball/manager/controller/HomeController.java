package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    private final WalkUpSongUploadService walkUpSongUploadService;

    public HomeController(PlayerService playerService,
                          GameWeekService gameWeekService,
                          RosterService rosterService,
                          WalkUpSongUploadService walkUpSongUploadService) {
        this.playerService = playerService;
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.walkUpSongUploadService = walkUpSongUploadService;
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

        /*
         * The browser path is already stored on the Player row. Exposing these
         * convenience values keeps the Thymeleaf template simple and lets the
         * player preview the exact MP3 that will be used during a game.
         */
        String walkUpFilePath = player.getWalkUpSongFilePath();
        boolean walkUpFileUploaded =
                walkUpFilePath != null && !walkUpFilePath.isBlank();
        model.addAttribute("walkUpFileUploaded", walkUpFileUploaded);
        model.addAttribute(
                "walkUpFileUrl",
                walkUpFileUploaded
                        ? walkUpSongUploadService.toPublicBrowserUrl(walkUpFilePath)
                        : null);
        model.addAttribute(
                "walkUpFilename",
                walkUpFileUploaded
                        ? walkUpSongUploadService.filenameForDisplay(walkUpFilePath)
                        : null);

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

    /**
     * Allows a player to upload or replace only their own walk-up MP3.
     *
     * <p>No player id is accepted from the browser. The destination player is
     * always resolved from the authenticated session, preventing one player
     * from replacing another player's audio by changing a form value.</p>
     */
    @PostMapping("/profile/walk-up-song/upload")
    public String uploadOwnWalkUpSong(
            @RequestParam("walkUpFile") MultipartFile walkUpFile,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        Player player = playerService.getCurrentPlayer(authentication);

        try {
            walkUpSongUploadService.uploadWalkUpSong(player.getId(), walkUpFile);
            redirectAttributes.addFlashAttribute(
                    "message",
                    "Your walk-up MP3 was uploaded successfully.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/";
    }
}