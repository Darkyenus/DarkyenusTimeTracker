package com.darkyen;

/**
 *
 */
@SuppressWarnings("WeakerAccess")
public final class TimeTrackerPersistentState {

    public long totalTimeSeconds = 0;

    public boolean autoStart = true;
    public long idleThresholdMs = 2 * 60 * 1000;
    public int autoCountIdleSeconds = 30;
    public boolean stopWhenIdleRatherThanPausing = false;
    public boolean pauseOtherTrackerInstances = true;

    public boolean gitIntegration = false;

    /** Bit field recording which features did we suggest user to enable. */
    public long naggedAbout = 0;

    public String ideTimePattern = DEFAULT_IDE_TIME_PATTERN;
    public String gitTimePattern = DEFAULT_GIT_TIME_PATTERN;

    public String gitRepoPath = DEFAULT_GIT_REPO_PATH;
    public String gitHooksPath = DEFAULT_GIT_HOOKS_PATH;

    public void setDefaultsFrom(final TimeTrackerPersistentState state) {
        this.autoStart = state.autoStart;
        this.idleThresholdMs = state.idleThresholdMs;
        this.autoCountIdleSeconds = state.autoCountIdleSeconds;
        this.stopWhenIdleRatherThanPausing = state.stopWhenIdleRatherThanPausing;
        this.pauseOtherTrackerInstances = state.pauseOtherTrackerInstances;

        this.ideTimePattern = state.ideTimePattern;
        this.gitTimePattern = state.gitTimePattern;
    }

    public static transient long NAGGED_ABOUT_GIT_INTEGRATION = 1;
    public static transient String DEFAULT_IDE_TIME_PATTERN = "{{lh \"hr\"s}} {{lm \"min\"}} {{ts \"sec\"}}";
    public static transient String DEFAULT_GIT_TIME_PATTERN = "Took {{lh \"hour\"s}} {{lm \"minute\"s}} {{ts \"second\"s}}";
    public static transient String DEFAULT_GIT_REPO_PATH = ".git";
    public static transient String DEFAULT_GIT_HOOKS_PATH = ".git/hooks";
}
