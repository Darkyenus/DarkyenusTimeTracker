package com.darkyen;

import com.intellij.openapi.components.*;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 *
 */
@State(
        name="DarkyenusTimeTracker",
        storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DEFAULT)}
        )
public final class TimeTrackerComponent implements ProjectComponent, PersistentStateComponent<TimeTrackerState>, Configurable {

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

    @Override
    public void projectOpened() {
        if (widget == null) {
            widget = new TimeTrackerWidget(project);
            if (lastStateCache != null) {
                widget.setState(lastStateCache);
                lastStateCache = null;
            }
            WindowManager.getInstance().getStatusBar(project).addWidget(widget);
        }
    }

    @Override
    public void projectClosed() {
        if (widget != null) {
            WindowManager.getInstance().getStatusBar(project).removeWidget(widget.ID());
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

    // Configurable

    @Nls
    @Override
    public String getDisplayName() {
        return "Darkyenus Time Tracker";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return null;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return null;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {

    }

    @Override
    public void reset() {

    }
}
