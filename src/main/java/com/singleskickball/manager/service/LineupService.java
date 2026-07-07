package com.singleskickball.manager.service;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Handles manual roster and batting-order changes.
 *
 * Team managers will use these actions after auto-roster generation to tweak
 * the batting order, remove someone who did not show up, or add a late arrival.
 */
@Service
public class LineupService {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public LineupService(PlayerRepository playerRepository,
                         TeamRepository teamRepository,
                         TeamRosterEntryRepository rosterEntryRepository) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    public List<TeamRosterEntry> getLineup(Team team) {
        return rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
    }

    @Transactional
    public void moveUp(Long rosterEntryId) {
        TeamRosterEntry entry = getRosterEntry(rosterEntryId);
        List<TeamRosterEntry> lineup = getNormalizedLineup(entry.getTeam());
        int index = lineup.indexOf(entry);
        if (index > 0) {
            swapBattingOrder(lineup.get(index), lineup.get(index - 1));
        }
    }

    @Transactional
    public void moveDown(Long rosterEntryId) {
        TeamRosterEntry entry = getRosterEntry(rosterEntryId);
        List<TeamRosterEntry> lineup = getNormalizedLineup(entry.getTeam());
        int index = lineup.indexOf(entry);
        if (index >= 0 && index < lineup.size() - 1) {
            swapBattingOrder(lineup.get(index), lineup.get(index + 1));
        }
    }

    @Transactional
    public void removeFromRoster(Long rosterEntryId) {
        TeamRosterEntry entry = getRosterEntry(rosterEntryId);
        Team team = entry.getTeam();
        rosterEntryRepository.delete(entry);
        normalizeBattingOrder(team);
    }

    @Transactional
    public void addPlayerToTeam(Long teamId, Long playerId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found."));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        GameWeek week = team.getGameWeek();
        if (rosterEntryRepository.existsByTeam_GameWeekAndPlayer(week, player)) {
            throw new IllegalArgumentException("Player is already assigned to a team for this week.");
        }

        List<TeamRosterEntry> lineup = getNormalizedLineup(team);
        TeamRosterEntry entry = new TeamRosterEntry();
        entry.setTeam(team);
        entry.setPlayer(player);
        entry.setBattingOrder(lineup.size() + 1);
        entry.setRunsScored(0);
        rosterEntryRepository.save(entry);
    }

    private TeamRosterEntry getRosterEntry(Long rosterEntryId) {
        return rosterEntryRepository.findById(rosterEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
    }

    private List<TeamRosterEntry> getNormalizedLineup(Team team) {
        normalizeBattingOrder(team);
        return rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
    }

    private void swapBattingOrder(TeamRosterEntry first, TeamRosterEntry second) {
        int firstOrder = first.getBattingOrder();
        first.setBattingOrder(second.getBattingOrder());
        second.setBattingOrder(firstOrder);
        rosterEntryRepository.save(first);
        rosterEntryRepository.save(second);
    }

    /**
     * Keeps batting order values consecutive: 1, 2, 3, ...
     *
     * This is useful after manual deletes or old data imports where batting
     * order values might have gaps.
     */
    private void normalizeBattingOrder(Team team) {
        List<TeamRosterEntry> entries = rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team)
                .stream()
                .sorted(Comparator.comparingInt(TeamRosterEntry::getBattingOrder).thenComparing(TeamRosterEntry::getId))
                .toList();

        for (int i = 0; i < entries.size(); i++) {
            TeamRosterEntry entry = entries.get(i);
            int expectedOrder = i + 1;
            if (entry.getBattingOrder() != expectedOrder) {
                entry.setBattingOrder(expectedOrder);
                rosterEntryRepository.save(entry);
            }
        }
    }
}
