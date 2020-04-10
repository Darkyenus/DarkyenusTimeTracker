package com.darkyen.actions;

import com.darkyen.TimeTrackerService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Reset Git time counter.
 */
public class ResetGitTimeAction extends AnAction {

	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		final Project project = e.getProject();
		if (project == null) {
			return;
		}

		final TimeTrackerService service = project.getService(TimeTrackerService.class);
		if (service == null) {
			return;
		}

		if (!service.isGitIntegration()) {
			return;
		}

		service.resetGitTime();
	}

	@Override
	public void update(@NotNull AnActionEvent e) {
		final Project project = e.getProject();
		if (project == null) {
			e.getPresentation().setEnabledAndVisible(false);
			return;
		}

		final TimeTrackerService service = project.getService(TimeTrackerService.class);
		if (service == null) {
			e.getPresentation().setEnabledAndVisible(false);
			return;
		}

		if (!service.isGitIntegration()) {
			e.getPresentation().setEnabledAndVisible(false);
			return;
		}

		e.getPresentation().setEnabledAndVisible(true);
	}

	@Override
	public boolean isDumbAware() {
		return true;
	}
}
