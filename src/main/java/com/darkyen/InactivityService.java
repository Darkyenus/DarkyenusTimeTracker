package com.darkyen;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Service listening for user activity to implement pause on inactivity.
 * <p>
 * Optimized to be O(1) with respect to the amount of opened projects.
 */
public final class InactivityService implements Disposable, AWTEventListener, PropertyChangeListener {

	private static final Logger LOG = Logger.getLogger(InactivityService.class.getName());

	@NotNull
	public static InactivityService getInstance() {
		return ApplicationManager.getApplication().getService(InactivityService.class);
	}

	private static final String AWT_ACTIVE_WINDOW = "activeWindow";

	private Object currentFocusedWindow;
	private final Map<Object, TimeTrackerService> projectWindowToTimeTrackerService = new ConcurrentHashMap<>();

	{
		// Init
		Toolkit.getDefaultToolkit().addAWTEventListener(this,
				AWTEvent.KEY_EVENT_MASK |
						AWTEvent.MOUSE_EVENT_MASK |
						AWTEvent.MOUSE_WHEEL_EVENT_MASK
		);

		final KeyboardFocusManager keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		keyboardFocusManager.addPropertyChangeListener(AWT_ACTIVE_WINDOW, this);
		currentFocusedWindow = keyboardFocusManager.getActiveWindow();
	}

	public void assignProjectWindow(@NotNull TimeTrackerService service, @Nullable Component componentInFrame) {
		if (componentInFrame == null) {
			componentInFrame = WindowManager.getInstance().getFrame(service.project);
			if (componentInFrame == null) {
				LOG.warning("Can't initialize listening - project has no window");
				return;
			}
		}

		final Object frameRoot = UIUtil.findUltimateParent(componentInFrame);

		projectWindowToTimeTrackerService.put(frameRoot, service);
		Disposer.register(service.project, ()-> {
			if (projectWindowToTimeTrackerService.get(frameRoot) == service) {
				projectWindowToTimeTrackerService.remove(frameRoot);
			}
		});
	}

	@Override
	public void dispose() {
		Toolkit.getDefaultToolkit().removeAWTEventListener(this);
		KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(AWT_ACTIVE_WINDOW, this);
	}

	@Override
	public void eventDispatched(AWTEvent event) {
		final Object currentFocusedWindow = this.currentFocusedWindow;
		if (currentFocusedWindow == null) {
			return;
		}

		final TimeTrackerService service = projectWindowToTimeTrackerService.get(currentFocusedWindow);
		if (service != null) {
			service.notifyUserNotIdle();
		}
	}

	@Override
	public void propertyChange(PropertyChangeEvent evt) {
		currentFocusedWindow = evt.getNewValue();
	}
}
