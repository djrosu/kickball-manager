package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamRosterEntryRepository extends JpaRepository<TeamRosterEntry, Long> {
    List<TeamRosterEntry> findByTeamOrderByBattingOrderAsc(Team team);
    List<TeamRosterEntry> findByTeam_GameWeek(GameWeek gameWeek);
    Optional<TeamRosterEntry> findByTeam_GameWeekAndPlayer(GameWeek gameWeek, Player player);

    @Query("select e.player.name, sum(e.runsScored) from TeamRosterEntry e group by e.player.id, e.player.name order by sum(e.runsScored) desc")
    List<Object[]> findRunLeaders();
}
