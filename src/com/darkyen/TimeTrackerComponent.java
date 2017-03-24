package com.darkyen;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
@State(
        name="DarkyenusTimeTracker",
        storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE)}
        )
public final class TimeTrackerComponent implements ProjectComponent, PersistentStateComponent<TimeTrackerState> {

    private static final Logger LOG = Logger.getLogger(TimeTrackerComponent.class.getName());

    private final Project project;
    private TimeTrackerWidget widget;

    private TimeTrackerState lastStateCache = null;

    public TimeTrackerComponent(Project project) {
        this.project = project;
    }

    @Override
    public void initComponent() {
    }

    @Override
    public void disposeComponent() {
    }

    @Override
    @NotNull
    public String getComponentName() {
        return "TimeTrackerComponent";
    }

    private StatusBar getProjectStatusBar() {
        final WindowManager windowManager = WindowManager.getInstance();
        if (windowManager == null) return null;
        return windowManager.getStatusBar(project);
    }

    @Override
    public void projectOpened() {
        if (widget == null) {
            widget = new TimeTrackerWidget(project);
            if (lastStateCache != null) {
                widget.setState(lastStateCache);
                lastStateCache = null;
            }
            final StatusBar statusBar = getProjectStatusBar();
            if (statusBar != null) {
                statusBar.addWidget(widget);
            } else {
                LOG.log(Level.SEVERE, "Can't initialize time tracking widget, status bar returned by IDE is null");
            }
        }
    }

    @Override
    public void projectClosed() {
        if (widget != null) {
            final StatusBar statusBar = getProjectStatusBar();
            if (statusBar != null) {
                statusBar.removeWidget(widget.ID());
            } else {
                LOG.log(Level.WARNING, "Can't remove time tracking widget, status bar returned by IDE is null");
            }
            lastStateCache = widget.getState();
        }
    }

    @Nullable
    @Override
    public TimeTrackerState getState() {
        if (widget != null) {
            return widget.getState();
        } else if(lastStateCache != null) {
            return lastStateCache;
        } else {
            return new TimeTrackerState();
        }
    }

    @Override
    public void loadState(TimeTrackerState state) {
        if (widget != null) {
            widget.setState(state);
        } else {
            lastStateCache = state;
        }
    }
}
