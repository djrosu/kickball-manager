package com.singleskickball.manager.dto;

/**
 * Public state describing which manager device currently owns game audio.
 */
public class AudioTargetState {

    private Long gameWeekId;
    private boolean targeted;
    private String deviceId;
    private String managerName;

    public AudioTargetState() { }

    public AudioTargetState(Long gameWeekId, boolean targeted, String deviceId, String managerName) {
        this.gameWeekId = gameWeekId;
        this.targeted = targeted;
        this.deviceId = deviceId;
        this.managerName = managerName;
    }

    public Long getGameWeekId() { return gameWeekId; }
    public void setGameWeekId(Long gameWeekId) { this.gameWeekId = gameWeekId; }
    public boolean isTargeted() { return targeted; }
    public void setTargeted(boolean targeted) { this.targeted = targeted; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
}
