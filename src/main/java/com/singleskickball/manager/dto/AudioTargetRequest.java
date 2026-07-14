package com.singleskickball.manager.dto;

/**
 * Request used when a manager claims or releases the shared audio target.
 *
 * <p>The browser creates one stable device id and keeps it in localStorage.
 * The server never trusts the display name for authorization; normal Spring
 * Security authentication and manager access checks still protect the action.</p>
 */
public class AudioTargetRequest {

    private Long gameWeekId;
    private String deviceId;

    public Long getGameWeekId() {
        return gameWeekId;
    }

    public void setGameWeekId(Long gameWeekId) {
        this.gameWeekId = gameWeekId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
}
