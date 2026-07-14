package com.singleskickball.manager.dto;

/**
 * JSON request body used by the manager action API.
 *
 * The API intentionally accepts one compact request object instead of relying
 * on browser form serialization. Each endpoint validates the identifiers it
 * needs. Keeping the request format consistent makes the JavaScript client much
 * easier to maintain and prepares the application for future live updates.
 */
public class ManagerActionRequest {

    /** Game week being managed. Required for game-level actions. */
    private Long gameWeekId;

    /** Team being managed. Required when adding a player or for team-manager game actions. */
    private Long teamId;

    /** Weekly roster entry being changed. Required for runs, order, and removal actions. */
    private Long rosterEntryId;

    /** Registered player being added to a team. */
    private Long playerId;

    /**
     * Stable browser/device id used only for temporary live-audio routing.
     * This is not persisted and is not a hardware identifier.
     */
    private String deviceId;

    public Long getGameWeekId() { return gameWeekId; }
    public void setGameWeekId(Long gameWeekId) { this.gameWeekId = gameWeekId; }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    public Long getRosterEntryId() { return rosterEntryId; }
    public void setRosterEntryId(Long rosterEntryId) { this.rosterEntryId = rosterEntryId; }

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
}
