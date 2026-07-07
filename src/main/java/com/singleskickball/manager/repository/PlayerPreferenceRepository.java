package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerPreferenceRepository extends JpaRepository<PlayerPreference, Long> {
    Optional<PlayerPreference> findByGameWeekAndPlayer(GameWeek gameWeek, Player player);
    List<PlayerPreference> findByGameWeek(GameWeek gameWeek);
}
