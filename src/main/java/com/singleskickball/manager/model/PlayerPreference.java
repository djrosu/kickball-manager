package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * Weekly teammate preference. Mutual preferences can be used by the roster
 * generator as a lower-priority tie breaker after team and gender balance.
 */
@Entity
@Table(name = "player_preferences",
       uniqueConstraints = @UniqueConstraint(columnNames = {"game_week_id", "player_id"}))
public class PlayerPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "game_week_id")
    private GameWeek gameWeek;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @ManyToOne(optional = false)
    @JoinColumn(name = "preferred_player_id")
    private Player preferredPlayer;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public GameWeek getGameWeek() { return gameWeek; }
    public void setGameWeek(GameWeek gameWeek) { this.gameWeek = gameWeek; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
    public Player getPreferredPlayer() { return preferredPlayer; }
    public void setPreferredPlayer(Player preferredPlayer) { this.preferredPlayer = preferredPlayer; }
}
