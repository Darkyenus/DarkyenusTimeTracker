package com.darkyen;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/** Provides the time tracker widget. */
public final class TimeTrackerWidgetFactory implements StatusBarWidgetFactory {

	@NotNull
	@Override
	public String getId() {
		return TimeTrackerWidget.ID;
	}

	@Nls
	@NotNull
	@Override
	public String getDisplayName() {
		return "Darkyen's Time Tracker";
	}

	@Override
	public boolean isAvailable(@NotNull Project project) {
		return project.getService(TimeTrackerService.class) != null;
	}

	@NotNull
	@Override
	public TimeTrackerWidget createWidget(@NotNull Project project) {
		return project.getService(TimeTrackerService.class).widget();
	}

	@Override
	public void disposeWidget(@NotNull StatusBarWidget widget) {
		// Nothing to dispose
	}

	@Override
	public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
		return true;
	}

}
