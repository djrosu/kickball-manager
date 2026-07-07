package com.singleskickball.manager.service;

import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import com.singleskickball.manager.repository.GameStateRepository;
import com.singleskickball.manager.repository.PlayerRepository;
import com.singleskickball.manager.repository.TeamRepository;
import com.singleskickball.manager.repository.TeamRosterEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Handles manual roster and batting-order changes.
 *
 * Team managers use these actions after auto-roster generation to tweak the
 * batting order, remove someone who did not show up, or add a late arrival.
 */
@Service
public class LineupService {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;
    private final GameStateRepository gameStateRepository;

    public LineupService(PlayerRepository playerRepository,
                         TeamRepository teamRepository,
                         TeamRosterEntryRepository rosterEntryRepository,
                         GameStateRepository gameStateRepository) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
        this.gameStateRepository = gameStateRepository;
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

    /**
     * Removes a player from the weekly roster.
     *
     * The game_state table may currently point at the roster entry being
     * removed through current_batter_roster_entry_id. If we delete the roster
     * entry first, the database rejects the delete because of that foreign key.
     *
     * To keep the game usable, this method:
     * 1. finds whether the removed entry is the current batter,
     * 2. clears that game-state reference before deleting,
     * 3. deletes the roster entry,
     * 4. normalizes the remaining batting order,
     * 5. chooses the next logical batter on the same team when possible.
     */
    @Transactional
    public void removeFromRoster(Long rosterEntryId) {
        TeamRosterEntry entry = getRosterEntry(rosterEntryId);
        Team team = entry.getTeam();

        List<TeamRosterEntry> lineupBeforeDelete = getNormalizedLineup(team);
        int removedIndex = findEntryIndex(lineupBeforeDelete, entry);

        GameState affectedGameState = gameStateRepository
                .findByCurrentBatterRosterEntry(entry)
                .orElse(null);

        if (affectedGameState != null) {
            // Break the FK relationship before deleting the roster entry.
            affectedGameState.setCurrentBatterRosterEntry(null);
            gameStateRepository.saveAndFlush(affectedGameState);
        }

        rosterEntryRepository.delete(entry);
        rosterEntryRepository.flush();

        normalizeBattingOrder(team);

        if (affectedGameState != null && isCurrentBattingTeam(affectedGameState, team)) {
            TeamRosterEntry nextBatter = chooseNextBatterAfterRemoval(team, removedIndex);
            affectedGameState.setCurrentBatterRosterEntry(nextBatter);
            gameStateRepository.save(affectedGameState);
        }
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

    private int findEntryIndex(List<TeamRosterEntry> lineup, TeamRosterEntry entry) {
        for (int i = 0; i < lineup.size(); i++) {
            if (Objects.equals(lineup.get(i).getId(), entry.getId())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCurrentBattingTeam(GameState gameState, Team team) {
        return gameState.getCurrentBattingTeam() != null
                && team != null
                && Objects.equals(gameState.getCurrentBattingTeam().getId(), team.getId());
    }

    private TeamRosterEntry chooseNextBatterAfterRemoval(Team team, int removedIndex) {
        List<TeamRosterEntry> remainingLineup = rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
        if (remainingLineup.isEmpty()) {
            return null;
        }

        if (removedIndex < 0 || removedIndex >= remainingLineup.size()) {
            return remainingLineup.get(0);
        }

        return remainingLineup.get(removedIndex);
    }
}
