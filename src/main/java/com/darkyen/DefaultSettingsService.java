package com.darkyen;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Used to track default settings. */
@State(name="DarkyenusTimeTrackerDefaults", storages = {@Storage("darkyenus-time-tracker-defaults.xml")})
public final class DefaultSettingsService implements PersistentStateComponent<TimeTrackerPersistentState> {

	private final TimeTrackerPersistentState defaultState = new TimeTrackerPersistentState();

	@NotNull
	@Override
	public TimeTrackerPersistentState getState() {
		return defaultState;
	}

	@Override
	public void loadState(@NotNull TimeTrackerPersistentState state) {
		this.defaultState.setDefaultsFrom(state);
	}

	public void setDefaultsFrom(@NotNull TimeTrackerPersistentState templateState) {
		this.defaultState.setDefaultsFrom(templateState);
	}

	@NotNull
	public static TimeTrackerPersistentState getDefaultState() {
		final DefaultSettingsService service = ApplicationManager.getApplication().getService(DefaultSettingsService.class);
		if (service == null) {
			return new TimeTrackerPersistentState();
		}
		return service.getState();
	}

	public static void setDefaultState(@NotNull TimeTrackerPersistentState state) {
		final DefaultSettingsService service = ApplicationManager.getApplication().getService(DefaultSettingsService.class);
		if (service == null) {
			return;
		}
		service.setDefaultsFrom(state);
	}
}
