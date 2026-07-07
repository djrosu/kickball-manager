package com.singleskickball.manager.service;

import com.singleskickball.manager.model.PasswordResetToken;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.PasswordResetTokenRepository;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and consumes password reset links.
 *
 * Security note: responses should not reveal whether an email address exists.
 */
@Service
public class PasswordResetService {

    private final PlayerRepository playerRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetService(PlayerRepository playerRepository,
                                PasswordResetTokenRepository tokenRepository,
                                EmailService emailService,
                                PasswordEncoder passwordEncoder) {
        this.playerRepository = playerRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void requestReset(String email, String baseUrl) {
        Optional<Player> playerOptional = playerRepository.findByEmailIgnoreCase(email);

        // Always return normally so attackers cannot use this page to discover registered emails.
        if (playerOptional.isEmpty()) {
            return;
        }

        Player player = playerOptional.get();

        // Expire older unused tokens for this player before issuing a new one.
        tokenRepository.findByPlayerAndUsedAtIsNull(player).forEach(existing -> {
            existing.setUsedAt(LocalDateTime.now());
            tokenRepository.save(existing);
        });

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setPlayer(player);
        resetToken.setToken(UUID.randomUUID().toString());
        resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(60));
        tokenRepository.save(resetToken);

        String resetUrl = baseUrl + "/reset-password?token=" + resetToken.getToken();
        emailService.sendPasswordResetEmail(player.getEmail(), resetUrl);
    }

    @Transactional(readOnly = true)
    public boolean isTokenValid(String token) {
        return tokenRepository.findByToken(token)
            .map(t -> !t.isExpired() && !t.isUsed())
            .orElse(false);
    }

    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<PasswordResetToken> tokenOptional = tokenRepository.findByToken(token);
        if (tokenOptional.isEmpty()) {
            return false;
        }

        PasswordResetToken resetToken = tokenOptional.get();
        if (resetToken.isExpired() || resetToken.isUsed()) {
            return false;
        }

        Player player = resetToken.getPlayer();
        player.setPasswordHash(passwordEncoder.encode(newPassword));
        playerRepository.save(player);

        resetToken.setUsedAt(LocalDateTime.now());
        tokenRepository.save(resetToken);
        return true;
    }
}
