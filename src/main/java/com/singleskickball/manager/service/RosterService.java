package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.RosterGenerationSummary;
import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Facade for roster-related operations used by existing controllers/pages.
 *
 * The first prototype put most roster logic directly in this service. As the
 * app grows, this class now delegates specialized work to:
 * - RosterBuilderService for auto-roster creation
 * - GameManagementService for live score/game operations
 * - LineupService for batting-order edits
 *
 * Keeping this facade lets older controller code keep working while we evolve
 * the internal design cleanly.
 */
@Service
public class RosterService {

    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;
    private final RosterBuilderService rosterBuilderService;
    private final GameManagementService gameManagementService;

    public RosterService(TeamRepository teamRepository,
                         TeamRosterEntryRepository rosterEntryRepository,
                         RosterBuilderService rosterBuilderService,
                         GameManagementService gameManagementService) {
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
        this.rosterBuilderService = rosterBuilderService;
        this.gameManagementService = gameManagementService;
    }

    public RosterGenerationSummary generateTwoTeamRoster(GameWeek week) {
        return rosterBuilderService.generateDefaultRoster(week);
    }

    public List<Team> getTeams(GameWeek week) {
        return teamRepository.findByGameWeekOrderByIdAsc(week);
    }

    public List<TeamRosterEntry> getRosterForTeam(Team team) {
        return rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
    }

    public List<TeamRosterEntry> getRosterForWeek(GameWeek week) {
        return rosterEntryRepository.findByTeam_GameWeekOrderByTeam_IdAscBattingOrderAsc(week);
    }

    public void addRun(Long rosterEntryId) {
        gameManagementService.addRun(rosterEntryId);
    }

    public void removeRun(Long rosterEntryId) {
        gameManagementService.removeRun(rosterEntryId);
    }

    public List<Object[]> getTopRunLeaders() {
        return rosterEntryRepository.findRunLeaders().stream().limit(10).toList();
    }
}
