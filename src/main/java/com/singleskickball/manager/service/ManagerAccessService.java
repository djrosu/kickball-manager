package com.singleskickball.manager.service;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.repository.TeamRepository;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Centralized authorization helper for manager workflows.
 *
 * Spring Security can protect URL patterns, but the manager rules for this app
 * are more specific than "is a manager":
 *
 * - League Supervisors can manage the entire league.
 * - Team Managers can only manage teams assigned to them for a particular game.
 *
 * Keeping those checks here prevents every controller from duplicating slightly
 * different permission logic.
 */
@Service
public class ManagerAccessService {

    private final PlayerService playerService;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public ManagerAccessService(PlayerService playerService,
                                TeamRepository teamRepository,
                                TeamRosterEntryRepository rosterEntryRepository) {
        this.playerService = playerService;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    /** Returns the logged-in Player entity. */
    public Player currentPlayer(Authentication authentication) {
        return playerService.getCurrentPlayer(authentication);
    }

    /** True when the player has full league-level administration rights. */
    public boolean isLeagueSupervisor(Player player) {
        return player != null && player.isMasterManager();
    }

    /**
     * Ensures the logged-in user is a League Supervisor.
     *
     * Use this before auto-roster generation, game administration, player/music
     * administration, or any operation that affects more than one team.
     */
    public Player requireLeagueSupervisor(Authentication authentication) {
        Player player = currentPlayer(authentication);
        if (!isLeagueSupervisor(player)) {
            throw new AccessDeniedException("League Supervisor access is required.");
        }
        return player;
    }

    /**
     * Returns the teams this player can manage for the selected game week.
     *
     * League Supervisors receive every team. Normal Team Managers receive only
     * teams where teams.manager_player_id points to their player record.
     */
    public List<Team> manageableTeams(GameWeek week, Player player) {
        if (isLeagueSupervisor(player)) {
            return teamRepository.findByGameWeekOrderByIdAsc(week);
        }
        List<Team> explicitlyAssigned = teamRepository.findByGameWeekAndManagerPlayerOrderByIdAsc(week, player);
        if (!explicitlyAssigned.isEmpty()) {
            return explicitlyAssigned;
        }

        // Compatibility fallback for existing rosters that were generated
        // before teams.manager_player_id was added.
        return teamRepository.findByGameWeekOrderByIdAsc(week).stream()
                .filter(team -> team.getManagerPlayer() == null)
                .filter(team -> player.isManager() && rosterEntryRepository.existsByTeamAndPlayer(team, player))
                .toList();
    }

    /**
     * Returns the game-week ids where this player is explicitly assigned as a
     * team manager. This is useful for League Supervisors because they may also
     * be assigned to one team, and their own game should be emphasized in the
     * supervisor experience.
     */
    public Set<Long> managedGameWeekIds(Player player) {
        if (player == null) {
            return Set.of();
        }
        return teamRepository.findManagedGameWeekIds(player);
    }

    /**
     * Finds the primary team a non-supervisor manager should see on the team
     * dashboard. For now a manager should normally have one assigned team per
     * game. This method still returns the first assigned team if the app later
     * allows a person to manage multiple teams.
     */
    public Team requirePrimaryManagedTeam(GameWeek week, Player player) {
        return manageableTeams(week, player).stream()
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("You are not assigned to manage a team for this game."));
    }

    /**
     * Ensures a user can manage the requested team.
     *
     * Controllers should use this before modifying rosters, batting order, runs,
     * or at-bat state from a team-specific page.
     */
    public Team requireTeamAccess(Long teamId, Player player) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found."));

        if (isLeagueSupervisor(player)) {
            return team;
        }

        Player manager = team.getManagerPlayer();
        if (manager != null && Objects.equals(manager.getId(), player.getId())) {
            return team;
        }

        // Compatibility fallback for teams created before Team.managerPlayer
        // existed. If this is an older team row with no explicit manager and
        // the current player is a manager who appears on that team's roster,
        // allow access. Auto-generated rosters going forward will populate the
        // manager_player_id column directly.
        if (manager == null && player.isManager() && rosterEntryRepository.existsByTeamAndPlayer(team, player)) {
            return team;
        }

        throw new AccessDeniedException("You can only manage your assigned team.");
    }

    /**
     * True when a team manager is allowed to act on this team's live at-bat.
     */
    public boolean canManageTeam(Player player, Team team) {
        if (team == null || player == null) {
            return false;
        }
        if (isLeagueSupervisor(player)) {
            return true;
        }
        if (team.getManagerPlayer() != null && Objects.equals(team.getManagerPlayer().getId(), player.getId())) {
            return true;
        }
        return team.getManagerPlayer() == null && player.isManager() && rosterEntryRepository.existsByTeamAndPlayer(team, player);
    }
}
