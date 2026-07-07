package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerAvailabilityRepository extends JpaRepository<PlayerAvailability, Long> {
    Optional<PlayerAvailability> findByGameWeekAndPlayer(GameWeek gameWeek, Player player);
    List<PlayerAvailability> findByGameWeekAndStatus(GameWeek gameWeek, AvailabilityStatus status);
    List<PlayerAvailability> findByGameWeek(GameWeek gameWeek);
}
