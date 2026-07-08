package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.GameWeekStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface GameWeekRepository extends JpaRepository<GameWeek, Long> {

    /**
     * Finds the next scheduled game on or after the supplied date.
     *
     * This method is still useful for historical fallback, but the primary
     * current-week lookup in GameWeekService now prefers an active in-progress
     * game first and ignores completed/final games.
     */
    Optional<GameWeek> findFirstByGameDateGreaterThanEqualOrderByGameDateAsc(LocalDate date);

    /**
     * Finds an active in-progress game. This protects the manager dashboard from
     * jumping to the next week while a game is still being played.
     */
    Optional<GameWeek> findFirstByStatusOrderByGameDateAsc(GameWeekStatus status);

    /**
     * Finds the next not-final game in schedule order. FINAL games are skipped
     * so once a manager ends a game, the app naturally moves to the next week.
     */
    Optional<GameWeek> findFirstByStatusNotAndGameDateGreaterThanEqualOrderByGameDateAsc(GameWeekStatus status,
                                                                                         LocalDate date);
}
