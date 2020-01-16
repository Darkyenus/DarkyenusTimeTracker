package com.darkyen;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Provides the time tracker widget. */
public class TimeTrackerWidgetProvider implements StatusBarWidgetProvider {

	@Nullable
	@Override
	public StatusBarWidget getWidget(@NotNull Project project) {
		final TimeTrackerService service = ServiceManager.getService(project, TimeTrackerService.class);
		if (service != null) {
			return service.widget();
		}
		return null;
	}

	@NotNull
	@Override
	public String getAnchor() {
		// anchor: Memory is a widget that seems to be always present and always at the rightmost corner.
		// (it may be hidden, but it is there)
		// If that changes in the future, the widget will still be added correctly, but maybe in a different location
		return "before Memory";
	}
}
