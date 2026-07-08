package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * A team for one scheduled game week.
 *
 * Teams are intentionally tied to a GameWeek because the league reforms teams
 * every week. The same player may be on Red one week and Yellow the next.
 *
 * The optional managerPlayer field identifies the player/manager assigned to
 * control this team during this game. A League Supervisor can still manage every
 * team, but normal Team Managers are limited to teams where they are assigned
 * here.
 */
@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "game_week_id")
    private GameWeek gameWeek;

    @Column(nullable = false)
    private String color;

    private String name;

    /**
     * Player/manager assigned to this team for this specific game.
     *
     * This is nullable for older rows and for future workflows where a team may
     * be created before a manager is assigned.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_player_id")
    private Player managerPlayer;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public GameWeek getGameWeek() { return gameWeek; }
    public void setGameWeek(GameWeek gameWeek) { this.gameWeek = gameWeek; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Player getManagerPlayer() { return managerPlayer; }
    public void setManagerPlayer(Player managerPlayer) { this.managerPlayer = managerPlayer; }
}
