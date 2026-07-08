package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.service.ManagerAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Entry point for manager workflows.
 *
 * The old application had one manager dashboard for everyone. The league now
 * has two manager experiences:
 *
 * - League Supervisor: full league/game administration.
 * - Team Manager: controls only their assigned team for the game.
 *
 * Keeping /manager as a smart redirect lets all existing links continue to work
 * while routing each user to the correct dashboard.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final ManagerAccessService accessService;

    public ManagerController(ManagerAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping
    public String managerHome(Authentication authentication) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        if (accessService.isLeagueSupervisor(currentPlayer)) {
            return "redirect:/manager/supervisor";
        }
        return "redirect:/manager/team";
    }
}
