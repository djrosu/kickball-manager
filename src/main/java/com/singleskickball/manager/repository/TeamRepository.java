package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access for weekly teams.
 *
 * Team manager filtering is based on Team.managerPlayer so the same user can
 * manage one team in one game and a different team in another game.
 *
 * IMPORTANT:
 * Team.managerPlayer is mapped lazily in the entity. The supervisor dashboard
 * displays manager names, so the finder methods used by manager screens eagerly
 * fetch managerPlayer. That prevents Thymeleaf from hitting Hibernate lazy-load
 * exceptions after the request transaction has closed.
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    /** Finds all teams for a game week with manager data available to the view. */
    @EntityGraph(attributePaths = "managerPlayer")
    List<Team> findByGameWeekOrderByIdAsc(GameWeek gameWeek);

    /** Finds teams assigned to a specific team manager for a given game week. */
    @EntityGraph(attributePaths = "managerPlayer")
    List<Team> findByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);

    /** Finds the first assigned team for a player in a week, used for My Team View routing. */
    @EntityGraph(attributePaths = "managerPlayer")
    Optional<Team> findFirstByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);

    /**
     * Returns every game week where this player is explicitly assigned as a team
     * manager. League Supervisors can also be team managers, and the supervisor
     * dashboard uses this to keep that supervisor's own game first/prominent.
     */
    @Query("select distinct t.gameWeek.id from Team t where t.managerPlayer = ?1")
    Set<Long> findManagedGameWeekIds(Player managerPlayer);
}
