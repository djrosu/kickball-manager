package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * Records whether a player is in or out for a specific week.
 */
@Entity
@Table(name = "player_availability",
       uniqueConstraints = @UniqueConstraint(columnNames = {"game_week_id", "player_id"}))
public class PlayerAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "game_week_id")
    private GameWeek gameWeek;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @Enumerated(EnumType.STRING)
    private AvailabilityStatus status = AvailabilityStatus.UNKNOWN;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public GameWeek getGameWeek() { return gameWeek; }
    public void setGameWeek(GameWeek gameWeek) { this.gameWeek = gameWeek; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
    public AvailabilityStatus getStatus() { return status; }
    public void setStatus(AvailabilityStatus status) { this.status = status; }
}
