package com.singleskickball.manager.controller;

import com.singleskickball.manager.service.PlayerService;
import com.singleskickball.manager.service.WalkUpSongUploadService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Manager-only page for uploading walk-up song MP3 files.
 *
 * Access is protected by SecurityConfig because every /manager/** route requires
 * a manager role. This controller focuses only on upload workflow; the actual
 * manager dashboard continues to play songs using WalkUpSongController's JSON
 * endpoint.
 */
@Controller
@RequestMapping("/manager/walk-up-songs")
public class ManagerWalkUpSongController {

    private final PlayerService playerService;
    private final WalkUpSongUploadService uploadService;

    public ManagerWalkUpSongController(PlayerService playerService,
                                       WalkUpSongUploadService uploadService) {
        this.playerService = playerService;
        this.uploadService = uploadService;
    }

    @GetMapping
    public String uploadPage(Model model) {
        model.addAttribute("players", playerService.findActivePlayers());
        return "manager/walk-up-songs";
    }

    @PostMapping("/{playerId}/upload")
    public String uploadSong(@PathVariable Long playerId,
                             MultipartFile file,
                             RedirectAttributes redirectAttributes) {
        try {
            uploadService.uploadWalkUpSong(playerId, file);
            redirectAttributes.addFlashAttribute("message", "Walk-up song uploaded.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/walk-up-songs";
    }

    @PostMapping("/{playerId}/clear")
    public String clearSong(@PathVariable Long playerId,
                            RedirectAttributes redirectAttributes) {
        try {
            uploadService.clearWalkUpSongFile(playerId);
            redirectAttributes.addFlashAttribute("message", "Walk-up song file cleared.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/walk-up-songs";
    }
}
