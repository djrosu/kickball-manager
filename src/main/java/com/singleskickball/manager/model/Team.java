package com.singleskickball.manager.model;

import jakarta.persistence.*;

/**
 * A weekly team, identified by color. More colors can be added later without
 * changing the domain model.
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public GameWeek getGameWeek() { return gameWeek; }
    public void setGameWeek(GameWeek gameWeek) { this.gameWeek = gameWeek; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
