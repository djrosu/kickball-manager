package com.singleskickball.manager.controller;

import com.singleskickball.manager.service.PasswordResetService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Public controller for requesting and consuming password reset links.
 *
 * The reset URL uses app.base-url instead of the current HTTP request so links
 * work correctly behind AWS load balancers and custom domains.
 */
@Controller
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final String appBaseUrl;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.passwordResetService = passwordResetService;
        this.appBaseUrl = removeTrailingSlash(appBaseUrl);
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam @Email String email,
                               RedirectAttributes redirectAttributes) {
        passwordResetService.requestReset(email, appBaseUrl);

        redirectAttributes.addFlashAttribute(
                "message",
                "If that email exists in the league, a password reset link has been sent."
        );

        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        if (!passwordResetService.isTokenValid(token)) {
            model.addAttribute("error", "This password reset link is invalid or expired.");
            return "reset-password";
        }

        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam @NotBlank String token,
                                @RequestParam @Size(min = 8) String password,
                                @RequestParam @Size(min = 8) String confirmPassword,
                                RedirectAttributes redirectAttributes,
                                Model model) {
        if (!password.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Passwords do not match.");
            return "reset-password";
        }

        boolean reset = passwordResetService.resetPassword(token, password);
        if (!reset) {
            model.addAttribute("error", "This password reset link is invalid or expired.");
            return "reset-password";
        }

        redirectAttributes.addFlashAttribute("message", "Your password has been updated. Please log in.");
        return "redirect:/login";
    }

    private String removeTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }

        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}