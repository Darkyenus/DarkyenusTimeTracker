package com.darkyen;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

/**
 * Only used to track default settings.
 */
@State(name="DarkyenusTimeTrackerDefaults", storages = {@Storage("darkyenus-time-tracker-defaults.xml")})
public class TimeTrackerDefaultSettingsComponent implements BaseComponent, PersistentStateComponent<TimeTrackerPersistentState> {

	private TimeTrackerPersistentState defaultState = new TimeTrackerPersistentState();

	@NotNull
	@Override
	public TimeTrackerPersistentState getState() {
		return defaultState;
	}

	@Override
	public void loadState(@NotNull TimeTrackerPersistentState state) {
		this.defaultState = state;
	}

	@NotNull
	@Override
	public String getComponentName() {
		return "DarkyenusTimeTrackerDefaults";
	}

	@NotNull
	public static TimeTrackerDefaultSettingsComponent instance() {
		final Application application = ApplicationManager.getApplication();
		final TimeTrackerDefaultSettingsComponent component = application
				.getComponent(TimeTrackerDefaultSettingsComponent.class);
		if (component == null) {
			return new TimeTrackerDefaultSettingsComponent();
		}
		return component;
	}
}
