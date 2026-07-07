package com.singleskickball.manager.service;

import com.singleskickball.manager.model.*;
import com.singleskickball.manager.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Creates and manages weekly rosters.
 *
 * The first version intentionally keeps the algorithm simple and readable:
 * one manager per team, then distribute available players by gender to keep
 * team balance reasonable. Mutual preference support can be expanded here.
 */
@Service
public class RosterService {

    private final PlayerAvailabilityRepository availabilityRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final TeamRosterEntryRepository rosterEntryRepository;

    public RosterService(PlayerAvailabilityRepository availabilityRepository,
                         PlayerRepository playerRepository,
                         TeamRepository teamRepository,
                         TeamRosterEntryRepository rosterEntryRepository) {
        this.availabilityRepository = availabilityRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.rosterEntryRepository = rosterEntryRepository;
    }

    @Transactional
    public void generateTwoTeamRoster(GameWeek week) {
        List<Team> teams = ensureDefaultTeams(week);
        List<Player> managers = playerRepository.findByManagerTrueAndActiveTrueOrderByNameAsc();
        List<PlayerAvailability> available = availabilityRepository.findByGameWeekAndStatus(week, AvailabilityStatus.IN);

        Set<Long> alreadyAssigned = new HashSet<>();
        int[] battingOrderCounters = new int[teams.size()];

        // Assign one manager to each team first.
        for (int i = 0; i < teams.size() && i < managers.size(); i++) {
            assignPlayerIfNeeded(week, teams.get(i), managers.get(i), ++battingOrderCounters[i]);
            alreadyAssigned.add(managers.get(i).getId());
        }

        // Split available non-manager players by gender so we can alternate distribution.
        List<Player> femalePlayers = new ArrayList<>();
        List<Player> malePlayers = new ArrayList<>();
        List<Player> otherPlayers = new ArrayList<>();

        for (PlayerAvailability item : available) {
            Player player = item.getPlayer();
            if (alreadyAssigned.contains(player.getId())) {
                continue;
            }
            if (player.getGender() == Gender.FEMALE) femalePlayers.add(player);
            else if (player.getGender() == Gender.MALE) malePlayers.add(player);
            else otherPlayers.add(player);
        }

        distributePlayersRoundRobin(week, teams, femalePlayers, battingOrderCounters);
        distributePlayersRoundRobin(week, teams, malePlayers, battingOrderCounters);
        distributePlayersRoundRobin(week, teams, otherPlayers, battingOrderCounters);
    }

    public List<Team> getTeams(GameWeek week) {
        return teamRepository.findByGameWeekOrderByIdAsc(week);
    }

    public List<TeamRosterEntry> getRosterForTeam(Team team) {
        return rosterEntryRepository.findByTeamOrderByBattingOrderAsc(team);
    }

    public List<TeamRosterEntry> getRosterForWeek(GameWeek week) {
        return rosterEntryRepository.findByTeam_GameWeek(week);
    }

    @Transactional
    public void addRun(Long rosterEntryId) {
        TeamRosterEntry entry = rosterEntryRepository.findById(rosterEntryId)
            .orElseThrow(() -> new IllegalArgumentException("Roster entry not found."));
        entry.setRunsScored(entry.getRunsScored() + 1);
        rosterEntryRepository.save(entry);
    }

    public List<Object[]> getTopRunLeaders() {
        return rosterEntryRepository.findRunLeaders().stream().limit(5).toList();
    }

    private List<Team> ensureDefaultTeams(GameWeek week) {
        List<Team> teams = teamRepository.findByGameWeekOrderByIdAsc(week);
        if (!teams.isEmpty()) {
            return teams;
        }

        Team red = new Team();
        red.setGameWeek(week);
        red.setColor("Red");
        red.setName("Red Team");

        Team yellow = new Team();
        yellow.setGameWeek(week);
        yellow.setColor("Yellow");
        yellow.setName("Yellow Team");

        return teamRepository.saveAll(List.of(red, yellow));
    }

    private void distributePlayersRoundRobin(GameWeek week, List<Team> teams, List<Player> players, int[] battingOrderCounters) {
        for (int i = 0; i < players.size(); i++) {
            int teamIndex = i % teams.size();
            assignPlayerIfNeeded(week, teams.get(teamIndex), players.get(i), ++battingOrderCounters[teamIndex]);
        }
    }

    private void assignPlayerIfNeeded(GameWeek week, Team team, Player player, int battingOrder) {
        rosterEntryRepository.findByTeam_GameWeekAndPlayer(week, player).ifPresentOrElse(
            existing -> { },
            () -> {
                TeamRosterEntry entry = new TeamRosterEntry();
                entry.setTeam(team);
                entry.setPlayer(player);
                entry.setBattingOrder(battingOrder);
                rosterEntryRepository.save(entry);
            }
        );
    }
}
