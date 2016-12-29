package com.darkyen;

/**
 *
 */
@SuppressWarnings("WeakerAccess")
public final class TimeTrackerState {
    public long totalTimeSeconds = 0;

    public boolean autoStart = true;
    public long idleThresholdMs = 2 * 60 * 1000;
    public boolean gitIntegration = false;
    public boolean pauseOtherTrackerInstances = true;

    /** Bit field recording which features did we suggest user to enable. */
    public long naggedAbout = 0;

    public static transient long NAGGED_ABOUT_GIT_INTEGRATION = 1;
}
