package com.singleskickball.manager.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Registered league participant.
 *
 * Managers are also players, so manager privileges are modeled as flags on the
 * Player record rather than as a separate user type.
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String name;

    private String nickname;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String phone;

    @Email
    @Column(nullable = false, unique = true)
    private String email;

    /** Stores a BCrypt hash, never a plain-text password. */
    @Column(nullable = false)
    private String passwordHash;

    private String walkUpSongArtist;
    private String walkUpSongTitle;
    private String walkUpSongFilePath;

    private boolean manager;
    private boolean masterManager;
    private boolean active = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getWalkUpSongArtist() { return walkUpSongArtist; }
    public void setWalkUpSongArtist(String walkUpSongArtist) { this.walkUpSongArtist = walkUpSongArtist; }
    public String getWalkUpSongTitle() { return walkUpSongTitle; }
    public void setWalkUpSongTitle(String walkUpSongTitle) { this.walkUpSongTitle = walkUpSongTitle; }
    public String getWalkUpSongFilePath() { return walkUpSongFilePath; }
    public void setWalkUpSongFilePath(String walkUpSongFilePath) { this.walkUpSongFilePath = walkUpSongFilePath; }
    public boolean isManager() { return manager; }
    public void setManager(boolean manager) { this.manager = manager; }
    public boolean isMasterManager() { return masterManager; }
    public void setMasterManager(boolean masterManager) { this.masterManager = masterManager; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
