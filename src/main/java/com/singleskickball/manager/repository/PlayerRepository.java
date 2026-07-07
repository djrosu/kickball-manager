package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByEmailIgnoreCase(String email);
    List<Player> findByActiveTrueOrderByNameAsc();
    List<Player> findByManagerTrueAndActiveTrueOrderByNameAsc();
}
