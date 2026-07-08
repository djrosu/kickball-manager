package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.WalkUpSongInfo;
import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import com.singleskickball.manager.service.GameManagementService;
import com.singleskickball.manager.service.GameWeekService;
import com.singleskickball.manager.service.LineupService;
import com.singleskickball.manager.service.ManagerAccessService;
import com.singleskickball.manager.service.PlayerService;
import com.singleskickball.manager.service.RosterService;
import com.singleskickball.manager.service.WalkUpSongService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Objects;

/**
 * Team Manager dashboard and actions.
 *
 * Normal Team Managers are player/managers. They may adjust only the team they
 * are assigned to for the selected game. League Supervisors normally use the
 * supervisor dashboard, but this controller also allows them through when they
 * are testing or helping a team manager.
 */
@Controller
@RequestMapping("/manager/team")
public class TeamManagerController {

    private final GameWeekService gameWeekService;
    private final RosterService rosterService;
    private final PlayerService playerService;
    private final LineupService lineupService;
    private final GameManagementService gameManagementService;
    private final WalkUpSongService walkUpSongService;
    private final ManagerAccessService accessService;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public TeamManagerController(GameWeekService gameWeekService,
                                 RosterService rosterService,
                                 PlayerService playerService,
                                 LineupService lineupService,
                                 GameManagementService gameManagementService,
                                 WalkUpSongService walkUpSongService,
                                 ManagerAccessService accessService,
                                 TeamRosterEntryRepository rosterEntryRepository) {
        this.gameWeekService = gameWeekService;
        this.rosterService = rosterService;
        this.playerService = playerService;
        this.lineupService = lineupService;
        this.gameManagementService = gameManagementService;
        this.walkUpSongService = walkUpSongService;
        this.accessService = accessService;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    /**
     * Shows the current user's assigned team dashboard.
     *
     * A League Supervisor is allowed to visit this page, but they may not be
     * assigned as a Team Manager for the current game. Previously that produced
     * a raw Whitelabel 403 page. This method now shows a friendly page explaining
     * that there is no team-specific view for the user yet.
     */
    @GetMapping
    public String dashboard(Model model, Authentication authentication) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        GameWeek week = gameWeekService.getCurrentGameWeek();
        List<Team> manageableTeams = accessService.manageableTeams(week, currentPlayer);

        if (manageableTeams.isEmpty()) {
            model.addAttribute("week", week);
            model.addAttribute("leagueSupervisor", accessService.isLeagueSupervisor(currentPlayer));
            model.addAttribute("message", "You are not assigned to manage a team for this game.");
            return "manager/no-team-assigned";
        }

        Team team = manageableTeams.get(0);
        GameState gameState = gameManagementService.getGameState(week).orElse(null);

        model.addAttribute("week", week);
        model.addAttribute("team", team);
        model.addAttribute("rosterEntries", rosterService.getRosterForTeam(team));
        model.addAttribute("players", playerService.findActivePlayers());
        model.addAttribute("gameState", gameState);
        model.addAttribute("scores", gameManagementService.getScores(week));
        model.addAttribute("currentWalkUpSong", currentTeamWalkUpSong(team, gameState));
        model.addAttribute("teamIsBatting", isTeamBatting(team, gameState));
        model.addAttribute("leagueSupervisor", accessService.isLeagueSupervisor(currentPlayer));

        return "manager/team-dashboard";
    }

    /** AJAX endpoint for the team manager's Next Batter button. */
    @PostMapping("/game/next-batter-info")
    @ResponseBody
    public WalkUpSongInfo nextBatterInfo(Authentication authentication) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        GameWeek week = gameWeekService.getCurrentGameWeek();
        Team team = accessService.requirePrimaryManagedTeam(week, currentPlayer);
        GameState before = gameManagementService.getGameState(week)
                .orElseThrow(() -> new IllegalStateException("Start the game before using live game controls."));

        if (!isTeamBatting(team, before)) {
            throw new AccessDeniedException("You can only advance your own team's batting order while your team is batting.");
        }

        GameState state = gameManagementService.nextBatter(week);
        return walkUpSongService.getWalkUpSongInfo(state);
    }

    /** Switches away from this team's at-bat when the side is retired. */
    @PostMapping("/game/end-at-bat")
    public String endAtBat(Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        GameWeek week = gameWeekService.getCurrentGameWeek();
        Team team = accessService.requirePrimaryManagedTeam(week, currentPlayer);
        GameState state = gameManagementService.getGameState(week).orElse(null);

        if (!isTeamBatting(team, state)) {
            redirectAttributes.addFlashAttribute("error", "Your team is not currently at bat.");
            return "redirect:/manager/team";
        }

        gameManagementService.endAtBat(week);
        redirectAttributes.addFlashAttribute("message", "At-bat ended. Switched teams.");
        return "redirect:/manager/team";
    }

    @PostMapping("/runs/{rosterEntryId}")
    public String addRun(@PathVariable Long rosterEntryId,
                         Authentication authentication) {
        requireRosterEntryAccess(rosterEntryId, authentication);
        rosterService.addRun(rosterEntryId);
        return "redirect:/manager/team";
    }

    @PostMapping("/runs/{rosterEntryId}/remove")
    public String removeRun(@PathVariable Long rosterEntryId,
                            Authentication authentication) {
        requireRosterEntryAccess(rosterEntryId, authentication);
        rosterService.removeRun(rosterEntryId);
        return "redirect:/manager/team";
    }

    @PostMapping("/lineup/{rosterEntryId}/up")
    public String movePlayerUp(@PathVariable Long rosterEntryId,
                               Authentication authentication) {
        requireRosterEntryAccess(rosterEntryId, authentication);
        lineupService.moveUp(rosterEntryId);
        return "redirect:/manager/team";
    }

    @PostMapping("/lineup/{rosterEntryId}/down")
    public String movePlayerDown(@PathVariable Long rosterEntryId,
                                 Authentication authentication) {
        requireRosterEntryAccess(rosterEntryId, authentication);
        lineupService.moveDown(rosterEntryId);
        return "redirect:/manager/team";
    }

    @PostMapping("/roster/{rosterEntryId}/remove")
    public String removeFromRoster(@PathVariable Long rosterEntryId,
                                   Authentication authentication) {
        requireRosterEntryAccess(rosterEntryId, authentication);
        lineupService.removeFromRoster(rosterEntryId);
        return "redirect:/manager/team";
    }

    @PostMapping("/teams/{teamId}/add-player")
    public String addPlayerToTeam(@PathVariable Long teamId,
                                  @RequestParam Long playerId,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        accessService.requireTeamAccess(teamId, currentPlayer);
        try {
            lineupService.addPlayerToTeam(teamId, playerId);
            redirectAttributes.addFlashAttribute("message", "Player added to roster.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/manager/team";
    }

    private TeamRosterEntry requireRosterEntryAccess(Long rosterEntryId, Authentication authentication) {
        Player currentPlayer = accessService.currentPlayer(authentication);
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
        accessService.requireTeamAccess(entry.getTeam().getId(), currentPlayer);
        return entry;
    }

    private WalkUpSongInfo currentTeamWalkUpSong(Team team, GameState gameState) {
        if (!isTeamBatting(team, gameState) || gameState.getCurrentBatterRosterEntry() == null) {
            return null;
        }
        return walkUpSongService.getWalkUpSongInfo(gameState);
    }

    private boolean isTeamBatting(Team team, GameState gameState) {
        return team != null
                && gameState != null
                && gameState.getCurrentBattingTeam() != null
                && Objects.equals(team.getId(), gameState.getCurrentBattingTeam().getId());
    }
}
