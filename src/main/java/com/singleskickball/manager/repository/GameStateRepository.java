package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameState;
import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.TeamRosterEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Stores the live state for one game week.
 *
 * A game state may reference the roster entry for the player currently at bat.
 * Before a roster entry is deleted, services should check whether a game state
 * points at it and clear/update that reference first so the database foreign
 * key is not violated.
 */
public interface GameStateRepository extends JpaRepository<GameState, Long> {

    Optional<GameState> findByGameWeek(GameWeek gameWeek);

    Optional<GameState> findByCurrentBatterRosterEntry(TeamRosterEntry currentBatterRosterEntry);

    void deleteByGameWeek(GameWeek gameWeek);
}
