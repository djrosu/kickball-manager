package com.singleskickball.manager.controller;

import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.RandomIntroService;
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
 * League Supervisor administration for the shared random intro collection.
 */
@Controller
@RequestMapping("/manager/random-intros")
public class RandomIntroController {

    private final RandomIntroService randomIntroService;
    private final ManagerAccessService accessService;

    public RandomIntroController(RandomIntroService randomIntroService,
                                 ManagerAccessService accessService) {
        this.randomIntroService = randomIntroService;
        this.accessService = accessService;
    }

    /** Lists every shared intro and its current eligibility. */
    @GetMapping
    public String page(Model model, Authentication authentication) {
        accessService.requireLeagueSupervisor(authentication);
        model.addAttribute("intros", randomIntroService.listIntros());
        return "manager/random-intros";
    }

    /** Uploads/replaces an MP3 and records its eligibility checkboxes. */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "false") boolean male,
                         @RequestParam(defaultValue = "false") boolean female,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);

        try {
            String filename = randomIntroService.upload(file, male, female);
            redirectAttributes.addFlashAttribute(
                    "message",
                    filename + " uploaded.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/manager/random-intros";
    }

    /** Changes male/female eligibility without replacing the MP3. */
    @PostMapping("/eligibility")
    public String updateEligibility(
            @RequestParam String filename,
            @RequestParam(defaultValue = "false") boolean male,
            @RequestParam(defaultValue = "false") boolean female,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);

        try {
            randomIntroService.updateEligibility(filename, male, female);
            redirectAttributes.addFlashAttribute(
                    "message",
                    "Eligibility updated for " + filename + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/manager/random-intros";
    }

    /** Deletes one shared intro and its eligibility metadata. */
    @PostMapping("/delete")
    public String delete(@RequestParam String filename,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        accessService.requireLeagueSupervisor(authentication);

        try {
            randomIntroService.delete(filename);
            redirectAttributes.addFlashAttribute(
                    "message",
                    filename + " deleted.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }

        return "redirect:/manager/random-intros";
    }
}
