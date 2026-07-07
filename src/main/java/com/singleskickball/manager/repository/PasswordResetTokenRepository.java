package com.singleskickball.manager.repository;

import com.singleskickball.manager.model.PasswordResetToken;
import com.singleskickball.manager.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    List<PasswordResetToken> findByPlayerAndUsedAtIsNull(Player player);
}
