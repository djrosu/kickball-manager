package com.singleskickball.manager.controller;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.ManagerDashboardStateService;
import com.singleskickball.manager.service.ManagerLiveUpdateService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Authenticated Server-Sent Events endpoint for manager dashboards.
 *
 * A Team Manager may subscribe only to a game in which they manage a team. A
 * League Supervisor may subscribe to any game. Browser cookies provide the same
 * Spring Security authentication used by the rest of the application.
 */
@RestController
@RequestMapping("/manager/api/live")
public class ManagerLiveUpdateController {

    private final GameWeekService gameWeekService;
    private final ManagerAccessService accessService;
    private final ManagerDashboardStateService dashboardStateService;
    private final ManagerLiveUpdateService liveUpdateService;

    public ManagerLiveUpdateController(GameWeekService gameWeekService,
                                       ManagerAccessService accessService,
                                       ManagerDashboardStateService dashboardStateService,
                                       ManagerLiveUpdateService liveUpdateService) {
        this.gameWeekService = gameWeekService;
        this.accessService = accessService;
        this.dashboardStateService = dashboardStateService;
        this.liveUpdateService = liveUpdateService;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam Long gameWeekId,
                             Authentication authentication) {
        GameWeek week = gameWeekService.getGameWeek(gameWeekId);
        Player currentPlayer = accessService.currentPlayer(authentication);

        // Reuse the existing role rules instead of trusting the browser.
        if (!accessService.isLeagueSupervisor(currentPlayer)) {
            accessService.requirePrimaryManagedTeam(week, currentPlayer);
        }

        SseEmitter emitter = liveUpdateService.subscribe(gameWeekId);

        // Publish is global, so send the initial state directly only to this client.
        // The initial Thymeleaf page is already correct; this mainly closes the
        // tiny race between page rendering and EventSource connection.
        try {
            emitter.send(SseEmitter.event()
                    .name("dashboard-state")
                    .data(dashboardStateService.buildState(week, "Live sync connected.")));
        } catch (Exception error) {
            emitter.completeWithError(error);
        }

        return emitter;
    }
}
