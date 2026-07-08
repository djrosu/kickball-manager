package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for weekly teams.
 *
 * Team manager filtering is based on Team.managerPlayer so the same user can
 * manage one team in one game and a different team in another game.
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByGameWeekOrderByIdAsc(GameWeek gameWeek);

    List<Team> findByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);

    Optional<Team> findFirstByGameWeekAndManagerPlayerOrderByIdAsc(GameWeek gameWeek, Player managerPlayer);
}
