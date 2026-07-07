package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByGameWeekOrderByIdAsc(GameWeek gameWeek);
}
