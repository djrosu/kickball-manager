package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for weekly teams.
 *
 * Team manager filtering is based on Team.managerPlayer so the same user can
 * manage one team in one game and a different team in another game.
 *
 * IMPORTANT:
 * Team.managerPlayer is mapped as LAZY in the Team entity. The supervisor
 * dashboard displays the assigned manager's name, so these repository methods
 * eagerly fetch managerPlayer with an EntityGraph. This prevents Thymeleaf from
 * trying to initialize a Hibernate proxy after the transaction/session is
 * already closed, which causes LazyInitializationException on the dashboard.
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    /**
     * Finds all teams for a game week and eagerly loads the assigned manager.
     *
     * The eager manager load is needed by the League Supervisor dashboard, which
     * displays the manager name for each team card.
     */
    @EntityGraph(attributePaths = "managerPlayer")
    List<Team> findByGameWeekOrderByIdAsc(GameWeek gameWeek);

    /**
     * Finds all teams managed by a specific player for a game week.
     *
     * This is used for team-manager routing and should also eagerly load the
     * manager relationship so templates can safely display manager information.
     */
    @EntityGraph(attributePaths = "managerPlayer")
    List<Team> findByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);

    /**
     * Finds the first team managed by a specific player for a game week.
     *
     * Used when deciding whether a League Supervisor also has a personal team
     * view available.
     */
    @EntityGraph(attributePaths = "managerPlayer")
    Optional<Team> findFirstByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);
}
