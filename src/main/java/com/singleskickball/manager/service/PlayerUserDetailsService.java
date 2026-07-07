package com.singleskickball.manager.service;

import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the Player table to Spring Security's login system.
 */
@Service
public class PlayerUserDetailsService implements UserDetailsService {

    private final PlayerRepository playerRepository;

    public PlayerUserDetailsService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Player player = playerRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new UsernameNotFoundException("No player found for email: " + email));

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_PLAYER"));
        if (player.isManager()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_MANAGER"));
        }
        if (player.isMasterManager()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_MASTER_MANAGER"));
        }

        return new User(player.getEmail(), player.getPasswordHash(), player.isActive(), true, true, true, authorities);
    }
}
