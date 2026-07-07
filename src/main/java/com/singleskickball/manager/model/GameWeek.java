package com.singleskickball.manager.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "game_weeks")
public class GameWeek {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate gameDate;

    private LocalTime gameTime;

    @Enumerated(EnumType.STRING)
    private GameWeekStatus status = GameWeekStatus.OPEN_FOR_AVAILABILITY;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getGameDate() { return gameDate; }
    public void setGameDate(LocalDate gameDate) { this.gameDate = gameDate; }

    public LocalTime getGameTime() { return gameTime; }
    public void setGameTime(LocalTime gameTime) { this.gameTime = gameTime; }

    public GameWeekStatus getStatus() { return status; }
    public void setStatus(GameWeekStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}