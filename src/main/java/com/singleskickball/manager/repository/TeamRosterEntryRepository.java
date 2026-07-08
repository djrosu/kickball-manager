package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import com.singleskickball.manager.model.TeamRosterEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Data access for weekly team roster entries.
 *
 * A TeamRosterEntry is week-specific. It stores the player's team assignment,
 * batting order, and runs scored for that week's game. Because each week's row
 * stores that week's runs, summing across rows gives season-long run totals.
 */
public interface TeamRosterEntryRepository extends JpaRepository<TeamRosterEntry, Long> {

    List<TeamRosterEntry> findByTeamOrderByBattingOrderAsc(Team team);

    List<TeamRosterEntry> findByTeam_GameWeek(GameWeek gameWeek);

    List<TeamRosterEntry> findByTeam_GameWeekOrderByTeam_IdAscBattingOrderAsc(GameWeek gameWeek);

    Optional<TeamRosterEntry> findByTeam_GameWeekAndPlayer(GameWeek gameWeek, Player player);

    Optional<TeamRosterEntry> findFirstByTeamOrderByBattingOrderAsc(Team team);

    boolean existsByTeam_GameWeekAndPlayer(GameWeek gameWeek, Player player);

    /**
     * Used as a compatibility fallback for teams created before the
     * manager_player_id column existed. If a manager is on the roster and the
     * team has no explicit manager assignment, we can still treat that manager
     * as responsible for that team until rosters are regenerated.
     */
    boolean existsByTeamAndPlayer(Team team, Player player);

    @Query("select e.team.id, coalesce(sum(e.runsScored), 0) " +
            "from TeamRosterEntry e " +
            "where e.team.gameWeek = ?1 " +
            "group by e.team.id")
    List<Object[]> findTeamRunTotals(GameWeek gameWeek);

    /** Top run leaders across all saved weekly roster entries. */
    @Query("select e.player.name, coalesce(sum(e.runsScored), 0) " +
            "from TeamRosterEntry e " +
            "group by e.player.id, e.player.name " +
            "order by coalesce(sum(e.runsScored), 0) desc, e.player.name asc")
    List<Object[]> findRunLeaders();

    /** Player-id keyed run totals used by the roster builder. */
    @Query("select e.player.id, coalesce(sum(e.runsScored), 0) " +
            "from TeamRosterEntry e " +
            "group by e.player.id")
    List<Object[]> findPlayerRunTotals();
}
