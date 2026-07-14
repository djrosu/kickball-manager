package com.singleskickball.manager.controller;

import com.singleskickball.manager.service.BetweenAtBatSongService;
import com.singleskickball.manager.service.ManagerAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * League Supervisor administration for music played while teams switch sides.
 */
@Controller
@RequestMapping("/manager/between-at-bat-songs")
public class BetweenAtBatSongController {

    private final BetweenAtBatSongService songService;
    private final ManagerAccessService accessService;

    public BetweenAtBatSongController(BetweenAtBatSongService songService,
                                      ManagerAccessService accessService) {
        this.songService = songService;
        this.accessService = accessService;
    }

    /** Shows the current alphabetical playlist. */
    @GetMapping
    public String page(Model model, Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        model.addAttribute("songs", songService.listSongs());
        return "manager/between-at-bat-songs";
    }

    /** Uploads or replaces one MP3 in /uploads/songs. */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);

        try {
            String filename = songService.upload(file);
            redirectAttributes.addFlashAttribute(
                    "message",
                    filename + " uploaded successfully.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/manager/between-at-bat-songs";
    }

    /** Deletes one MP3 from /uploads/songs. */
    @PostMapping("/delete")
    public String delete(@RequestParam String filename,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);

        try {
            songService.delete(filename);
            redirectAttributes.addFlashAttribute(
                    "message",
                    filename + " deleted.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/manager/between-at-bat-songs";
    }
}
