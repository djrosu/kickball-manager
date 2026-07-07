package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Stores the live state for one game week.
 */
public interface GameStateRepository extends JpaRepository<GameState, Long> {
    Optional<GameState> findByGameWeek(GameWeek gameWeek);
    void deleteByGameWeek(GameWeek gameWeek);
}
