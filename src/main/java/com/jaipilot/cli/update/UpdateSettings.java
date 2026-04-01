package com.jaipilot.cli.update;

public record UpdateSettings(
        Boolean autoUpdates,
        Long lastCheckEpochSecond
) {

    public boolean autoUpdatesEnabled(boolean defaultValue) {
        return autoUpdates == null ? defaultValue : autoUpdates;
    }
}
