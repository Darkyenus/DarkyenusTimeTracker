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
    private StatusBar widgetStatusBar;

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

    @Override
    public void projectOpened() {
        if (widget == null) {
            final WindowManager windowManager = WindowManager.getInstance();
            if (windowManager == null) {
                LOG.log(Level.SEVERE, "Can't initialize time tracking widget, WindowManager is null");
                return;
            }
            final StatusBar statusBar = windowManager.getStatusBar(project);
            if (statusBar == null) {
                LOG.log(Level.SEVERE, "Can't initialize time tracking widget, status bar returned by IDE is null");
                return;
            }

            final TimeTrackerWidget widget = new TimeTrackerWidget(project);
            if (lastStateCache != null) {
                widget.setState(lastStateCache);
                lastStateCache = null;
            }
            statusBar.addWidget(widget);
            this.widget = widget;
            this.widgetStatusBar = statusBar;
        }
    }

    @Override
    public void projectClosed() {
        if (widget != null && this.widgetStatusBar != null) {
            widgetStatusBar.removeWidget(widget.ID());
            lastStateCache = widget.getState();
            this.widget = null;
            this.widgetStatusBar = null;
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
