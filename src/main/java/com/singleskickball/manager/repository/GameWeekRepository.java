package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.GameWeek;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface GameWeekRepository extends JpaRepository<GameWeek, Long> {

    /**
     * Finds the next scheduled game on or after the supplied date.
     *
     * This is used for the player and manager dashboards so we show the
     * upcoming game instead of accidentally showing the last game in the season.
     */
    Optional<GameWeek> findFirstByGameDateGreaterThanEqualOrderByGameDateAsc(LocalDate date);
}