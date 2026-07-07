package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Data access for weekly roster entries.
 *
 * Roster entries are where we store the week-specific batting order and run
 * totals. A player can appear on different teams in different weeks, but only
 * once within a single game week.
 */
public interface TeamRosterEntryRepository extends JpaRepository<TeamRosterEntry, Long> {

    List<TeamRosterEntry> findByTeamOrderByBattingOrderAsc(Team team);

    List<TeamRosterEntry> findByTeam_GameWeek(GameWeek gameWeek);

    List<TeamRosterEntry> findByTeam_GameWeekOrderByTeam_IdAscBattingOrderAsc(GameWeek gameWeek);

    Optional<TeamRosterEntry> findByTeam_GameWeekAndPlayer(GameWeek gameWeek, Player player);

    Optional<TeamRosterEntry> findFirstByTeamOrderByBattingOrderAsc(Team team);

    boolean existsByTeam_GameWeekAndPlayer(GameWeek gameWeek, Player player);

    @Query("select e.team.id, coalesce(sum(e.runsScored), 0) from TeamRosterEntry e where e.team.gameWeek = ?1 group by e.team.id")
    List<Object[]> findTeamRunTotals(GameWeek gameWeek);

    @Query("select e.player.name, sum(e.runsScored) from TeamRosterEntry e group by e.player.id, e.player.name order by sum(e.runsScored) desc")
    List<Object[]> findRunLeaders();
}
