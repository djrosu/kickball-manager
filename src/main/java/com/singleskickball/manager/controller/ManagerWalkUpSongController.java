package com.singleskickball.manager.controller;

import com.singleskickball.manager.service.WalkUpSongUploadService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Manager-only controller for uploading and maintaining walk-up audio.
 *
 * SecurityConfig protects /manager/**, so this controller does not need to do
 * role checks itself. It simply exposes the upload workflow under the manager
 * area of the application.
 */
@Controller
@RequestMapping("/manager/walk-up-songs")
public class ManagerWalkUpSongController {

    private final WalkUpSongUploadService uploadService;

    public ManagerWalkUpSongController(WalkUpSongUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * Shows all active players with their intro and walk-up song upload status.
     */
    @GetMapping
    public String uploadPage(Model model) {
        model.addAttribute("rows", uploadService.getUploadRows());
        return "manager/walk-up-songs";
    }

    /**
     * Uploads/replaces the player's walk-up song MP3 and updates the Player row.
     */
    @PostMapping("/{playerId}/upload")
    public String uploadSong(@PathVariable Long playerId,
                             @RequestParam("file") MultipartFile file,
                             RedirectAttributes redirectAttributes) {
        try {
            uploadService.uploadWalkUpSong(playerId, file);
            redirectAttributes.addFlashAttribute("message", "Walk-up song uploaded.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/walk-up-songs";
    }

    /**
     * Uploads/replaces the player's intro MP3. No database column is needed;
     * intro files are named {playerId}.mp3.
     */
    @PostMapping("/{playerId}/intro/upload")
    public String uploadIntro(@PathVariable Long playerId,
                              @RequestParam("introFile") MultipartFile introFile,
                              RedirectAttributes redirectAttributes) {
        try {
            uploadService.uploadIntro(playerId, introFile);
            redirectAttributes.addFlashAttribute("message", "Intro MP3 uploaded.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/walk-up-songs";
    }

    /**
     * Clears the current walk-up song link for a player.
     */
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

    /**
     * Deletes the convention-based intro file for a player.
     */
    @PostMapping("/{playerId}/intro/clear")
    public String clearIntro(@PathVariable Long playerId,
                             RedirectAttributes redirectAttributes) {
        try {
            uploadService.clearIntro(playerId);
            redirectAttributes.addFlashAttribute("message", "Intro MP3 cleared.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/walk-up-songs";
    }
}
