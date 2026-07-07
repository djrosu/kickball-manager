package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.TeamRosterEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Data access for the live state of a game week.
 *
 * The game state stores the currently batting team, current batter, and inning.
 * Services use findByCurrentBatterRosterEntry before deleting a roster entry so
 * the foreign-key reference can be cleared or moved safely first.
 */
public interface GameStateRepository extends JpaRepository<GameState, Long> {

    Optional<GameState> findByGameWeek(GameWeek gameWeek);

    Optional<GameState> findByCurrentBatterRosterEntry(TeamRosterEntry currentBatterRosterEntry);

    void deleteByGameWeek(GameWeek gameWeek);
}
