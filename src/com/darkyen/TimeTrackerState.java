package com.darkyen;

/**
 *
 */
@SuppressWarnings("WeakerAccess")
public final class TimeTrackerState {
    public long totalTimeSeconds = 0;

    public boolean autoStart = true;
    public long idleThresholdMs = 2 * 60 * 1000;
}
