package com.darkyen;

import com.intellij.AppTopics;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDialog;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.darkyen.GitIntegration.SetupCommitHookResult.GIT_DIR_NOT_FOUND;
import static com.darkyen.GitIntegration.SetupCommitHookResult.GIT_HOOKS_DIR_NOT_FOUND;
import static com.darkyen.Util.convertToIOFile;
import static com.darkyen.Util.getProjectBaseDir;
import static com.darkyen.Util.msToS;

/**
 * Every entry-point method of this class is to be synchronized.
 * <p>
 * Each operation that needs to be on a particular thread must do it itself.
 */
@State(name="DarkyenusTimeTracker", storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE)})
public final class TimeTrackerService implements PersistentStateComponent<TimeTrackerPersistentState>, Disposable {

	private static final Logger LOG = Logger.getLogger(TimeTrackerService.class.getName());
	private static final boolean DEBUG_LIFECYCLE = false;

	public static final long RESET_TIME_TO_ZERO = Long.MIN_VALUE;

	private static final String NOTIFICATION_GROUP_ID = "Darkyenus Time Tracker";

	private static final String IDLE_NOTIFICATION_GROUP_ID = "Darkyenus Time Tracker - Idle time";
	public static final TimePattern NOTIFICATION_TIME_FORMATTING = TimePattern.parse("{{lw \"week\"s}} {{ld \"day\"s}} {{lh \"hour\"s}} {{lm \"minute\"s}} {{ts \"second\"s}}");

	@NotNull
	public final Project project;

	@Nullable
	private GitIntegration gitIntegrationComponent;

	@Nullable
	private TimeTrackerWidget widget;

	private long totalTimeMs = 0;
	private volatile TimeTrackingStatus status = TimeTrackingStatus.STOPPED;
	private long statusStartedMs = System.currentTimeMillis();
	private long lastTickMs = System.currentTimeMillis();
	private volatile long lastActivityMs = System.currentTimeMillis();

	private boolean autoStart;
	private long idleThresholdMs;
	private int autoCountIdleSeconds;
	private boolean stopWhenIdleRatherThanPausing;
	private boolean pauseOtherTrackerInstances;

	private boolean gitIntegration;
	@NotNull
	private String gitRepoPath = TimeTrackerPersistentState.DEFAULT_GIT_REPO_PATH;
	@NotNull
	private String gitHooksPath = TimeTrackerPersistentState.DEFAULT_GIT_HOOKS_PATH;

	private long naggedAbout = 0;

	// Nullable only until initialization is done
	@Nullable
	private TimePattern ideTimePattern;
	@Nullable
	private TimePattern gitTimePattern;

	@Nullable
	private ScheduledFuture<?> ticker;

	private static final long TICK_DELAY = 1;
	private static final TimeUnit TICK_DELAY_UNIT = TimeUnit.SECONDS;
	private static final long TICK_JUMP_DETECTION_THRESHOLD_MS = TICK_DELAY_UNIT.toMillis(TICK_DELAY * 20);

	private static final Set<TimeTrackerService> ALL_OPENED_TRACKERS = ContainerUtil.newConcurrentSet();

	public TimeTrackerService(@NotNull Project project) {
		if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "Instantiated "+this);
		this.project = project;
		Disposer.register(project, this);

		ALL_OPENED_TRACKERS.add(this);
		project.getMessageBus().connect(this).subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListener() {
				@Override
				public void beforeAllDocumentsSaving() {
					saveTime();
				}

				@Override
				public void beforeDocumentSaving(@NotNull Document document) {
					saveTime();
				}

				// Default methods in 2018.3, but would probably crash in earlier versions
				@Override
				public void beforeFileContentReload(@NotNull VirtualFile file, @NotNull Document document) {
				}

				@Override
				public void fileWithNoDocumentChanged(@NotNull VirtualFile file) {
				}

				@Override
				public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
				}

				@Override
				public void fileContentLoaded(@NotNull VirtualFile file, @NotNull Document document) {
				}

				@Override
				public void unsavedDocumentsDropped() {
				}
			});

		EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
			@Override
			public void documentChanged(@NotNull DocumentEvent e) {
				if (!isAutoStart() || getStatus() == TimeTrackingStatus.RUNNING) return;
				//getSelectedTextEditor() must be run from event dispatch thread
				EventQueue.invokeLater(() -> {
					final Project project = project();
					if (project == null) return;

					final Editor selectedTextEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
					if (selectedTextEditor == null) return;
					if(e.getDocument().equals(selectedTextEditor.getDocument())) {
						setStatus(TimeTrackingStatus.RUNNING);
					}
				});
			}
		}, this);

		InactivityService.getInstance().assignProjectWindow(this, null);
	}

	@NotNull
	public TimeTrackerWidget widget() {
		TimeTrackerWidget widget;
		synchronized (this) {
			widget = this.widget;
			if (widget == null) {
				this.widget = widget = new TimeTrackerWidget(this);
			}
		}
		return widget;
	}

	@NotNull
	public TimeTrackingStatus getStatus() {
		return status;
	}

	public synchronized void toggleRunning() {
		switch (this.status) {
			case RUNNING:
				setStatus(TimeTrackingStatus.STOPPED);
				break;
			case STOPPED:
			case IDLE:
				setStatus(TimeTrackingStatus.RUNNING);
				break;
		}
	}

	private synchronized void tick() {
		if (status != TimeTrackingStatus.RUNNING) {
			LOG.warning("Tick when status is "+status);
			return;
		}

		final long now = System.currentTimeMillis();
		final long sinceLastTickMs = now - lastTickMs;
		final long lastActivityMs = this.lastActivityMs;
		final long sinceLastActivityMs = now - lastActivityMs;

		if (sinceLastTickMs > TICK_JUMP_DETECTION_THRESHOLD_MS) {
			final long lastValidTimeMs = lastTickMs + TICK_JUMP_DETECTION_THRESHOLD_MS;
			setStatus(stopWhenIdleRatherThanPausing ? TimeTrackingStatus.STOPPED : TimeTrackingStatus.IDLE, lastValidTimeMs);
		} else if (sinceLastActivityMs >= idleThresholdMs) {
			final long lastValidTimeMs = lastActivityMs + idleThresholdMs;
			setStatus(stopWhenIdleRatherThanPausing ? TimeTrackingStatus.STOPPED : TimeTrackingStatus.IDLE, lastValidTimeMs);
		}

		lastTickMs = now;
		repaintWidget(false);
	}

	private synchronized void otherComponentStarted() {
		if (status != TimeTrackingStatus.STOPPED) {
			setStatus(TimeTrackingStatus.IDLE);
		}
	}

	public synchronized void addOrResetTotalTimeMs(long milliseconds) {
		if (milliseconds == RESET_TIME_TO_ZERO) {
			totalTimeMs = 0L;
			statusStartedMs = System.currentTimeMillis();
			updateGitTime(RESET_TIME_TO_ZERO);
		} else {
			addTotalTimeMs(milliseconds);
		}
		repaintWidget(false);
	}

	public synchronized void resetGitTime() {
		updateGitTime(RESET_TIME_TO_ZERO);
	}

	private synchronized void addTotalTimeMs(long milliseconds) {
		totalTimeMs = Math.max(0L, totalTimeMs + milliseconds);
		updateGitTime(msToS(milliseconds));
	}

	private synchronized void saveTime() {
		if (status == TimeTrackingStatus.RUNNING) {
			final long now = System.currentTimeMillis();
			final long msInState = Math.max(0L, now - statusStartedMs);
			statusStartedMs = now;
			addTotalTimeMs(msInState);
		}
	}

	public synchronized void setStatus(@NotNull TimeTrackingStatus status) {
		setStatus(status, System.currentTimeMillis());
	}

	private void setStatus(final @NotNull TimeTrackingStatus status, final long now) {
		if (this.status == status) {
			return;
		}

		if (ticker != null) {
			ticker.cancel(false);
			ticker = null;
		}

		final long msInState = Math.max(0L, now - statusStartedMs);

		switch (this.status) {
			case RUNNING: {
				addTotalTimeMs(msInState);
				break;
			}
			case IDLE: {
				if (msToS(msInState) <= autoCountIdleSeconds) {
					addTotalTimeMs(msInState);
				} else if (msInState > 1000) {
					final Project project = project();
					if (project != null) {
						final Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(IDLE_NOTIFICATION_GROUP_ID)
								.createNotification(
								"Gone for <b>" + NOTIFICATION_TIME_FORMATTING.millisecondsToString(msInState) + "</b>",
								NotificationType.INFORMATION);

						notification.addAction(new AnAction("Count this time in") {

							private boolean primed = true;

							@Override
							public void actionPerformed(@NotNull AnActionEvent e) {
								if (primed) {
									addTotalTimeMs(msInState);
									repaintWidget(false);
									primed = false;
									getTemplatePresentation().setText("Already counted in");
									e.getPresentation().setText("Counted in");
									notification.expire();
								}
							}
						});

						Notifications.Bus.notify(notification, project);
					}
				}
				break;
			}
		}

		this.statusStartedMs = now;
		this.lastTickMs = now;
		this.lastActivityMs = now;
		this.status = status;

		if (status == TimeTrackingStatus.RUNNING) {
			if (pauseOtherTrackerInstances) {
				ALL_OPENED_TRACKERS.forEach(tracker -> {
					if (tracker != this) {
						tracker.otherComponentStarted();
					}
				});
			}

			ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(this::tick, TICK_DELAY, TICK_DELAY, TICK_DELAY_UNIT);
		}

		repaintWidget(false);
	}

	public synchronized int getTotalTimeSeconds() {
		long resultMs = this.totalTimeMs;
		if (this.status == TimeTrackingStatus.RUNNING) {
			final long now = System.currentTimeMillis();
			resultMs += Math.max(0L, now - statusStartedMs);
		}

		return (int) msToS(resultMs);
	}

	public boolean isAutoStart() {
		return autoStart;
	}

	public synchronized void setAutoStart(boolean autoStart) {
		this.autoStart = autoStart;
	}

	public long getIdleThresholdMs() {
		return idleThresholdMs;
	}

	public synchronized void setIdleThresholdMs(long idleThresholdMs) {
		this.idleThresholdMs = idleThresholdMs;
	}

	public int getAutoCountIdleSeconds() {
		return autoCountIdleSeconds;
	}

	public synchronized void setAutoCountIdleSeconds(int autoCountIdleSeconds) {
		this.autoCountIdleSeconds = autoCountIdleSeconds;
	}

	public boolean isStopWhenIdleRatherThanPausing() {
		return stopWhenIdleRatherThanPausing;
	}

	public void setStopWhenIdleRatherThanPausing(boolean stopWhenIdleRatherThanPausing) {
		this.stopWhenIdleRatherThanPausing = stopWhenIdleRatherThanPausing;
	}

	public boolean isGitIntegration() {
		return gitIntegration;
	}

	public synchronized Boolean setGitIntegration(boolean enable) {
		final Path projectBase;
		{
			final Project project = project();
			if (project == null) {
				return false;
			}
			projectBase = convertToIOFile(getProjectBaseDir(project));
			if (projectBase == null) {
				if (enable) {
					Notifications.Bus.notify(NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
							"Failed to enable git integration",
							"Project is not on a local filesystem",
							NotificationType.WARNING), project);
				}
				return false;
			}
		}

		GitIntegration gitIntegrationComponent = this.gitIntegrationComponent;
		if (gitIntegrationComponent == null) {
			// Create it
			final Path gitDir = projectBase.resolve(gitRepoPath);
			final Path gitHooksDir = projectBase.resolve(gitHooksPath);
			gitIntegrationComponent = this.gitIntegrationComponent = new GitIntegration(gitDir, gitHooksDir);
		}

		final GitIntegration.SetupCommitHookResult result = gitIntegrationComponent.setupCommitHook(enable);
		switch (result) {
			case SUCCESS: {
				if (enable) {
					updateGitTime(0);
				} else {
					nagAboutGitIntegrationIfNeeded();
				}
				return this.gitIntegration = enable;
			}
			case INTERNAL_ERROR:
			case HOOK_ALREADY_EXISTS: {
				Notifications.Bus.notify(NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
						"Failed to " + (enable ? "enable" : "disable") + " git integration",
						result.message,
						NotificationType.WARNING), project());
				return this.gitIntegration = false;
			}
			case GIT_DIR_NOT_FOUND:
			case GIT_HOOKS_DIR_NOT_FOUND: {
				final Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
						"Failed to enable git integration",
						result == GIT_DIR_NOT_FOUND ? "'.git' directory not found" : "'.git/hooks' directory not found",
						NotificationType.WARNING);
				notification.addAction(new AnAction("Find manually") {
					@Override
					public void actionPerformed(@NotNull AnActionEvent e) {
						if (notification.isExpired()) {
							return;
						}
						notification.expire();

						ApplicationManager.getApplication().invokeLater(() -> {
							final Project project = project();
							if (project == null) return;

							final FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false)
									.withShowHiddenFiles(true)
									.withHideIgnored(false)
									.withRoots(getProjectBaseDir(project))
									.withTitle(result == GIT_DIR_NOT_FOUND ? "Select .git Directory" : "Select .git/hooks Directory");
							try {
								fileChooserDescriptor.setForcedToUseIdeaFileChooser(true); // Mac one has problems with hidden files, making the whole thing pointless
								if (result == GIT_HOOKS_DIR_NOT_FOUND) {
									fileChooserDescriptor.withRoots(VirtualFileManager.getInstance().findFileByUrl(projectBase.resolve(gitRepoPath).toUri().toURL().toString()));
								}
							} catch (Throwable t) {
								LOG.log(Level.WARNING, "Failed to fully configure FCD for "+result, t);
							}

							@SuppressWarnings("DialogTitleCapitalization")
							final FileChooserDialog fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, project, null);

							final VirtualFile[] chosen = fileChooser.choose(project);
							Path chosenPath = null;
							if (chosen.length >= 1) {
								chosenPath = convertToIOFile(chosen[0]);
							}
							if (chosenPath == null) {
								LOG.log(Level.INFO, "No valid path chosen " + Arrays.toString(chosen));
								return;
							}

							// Users (I) often select parent directory instead of .git directory, detect that and fix that.
							if (result == GIT_DIR_NOT_FOUND && !chosenPath.getFileName().toString().equalsIgnoreCase(".git")) {
								// User has not chosen a .git directory, but maybe the parent directory? Common mistake.
								final Path possiblyGitDirectory = chosenPath.resolve(".git");
								if (Files.isDirectory(possiblyGitDirectory)) {
									chosenPath = possiblyGitDirectory;
								}
							}

							synchronized (TimeTrackerService.this) {
								if (chosenPath.startsWith(projectBase)) {
									chosenPath = projectBase.relativize(chosenPath);
								} else {
									chosenPath = chosenPath.toAbsolutePath();
								}

								TimeTrackerService.this.gitIntegrationComponent = null;

								String chosenPathString = chosenPath.toString();
								if (result == GIT_DIR_NOT_FOUND) {
									TimeTrackerService.this.gitRepoPath = chosenPathString;
									TimeTrackerService.this.gitHooksPath = chosenPath.resolve("hooks").toString();
								} else if (result == GIT_HOOKS_DIR_NOT_FOUND) {
									if (chosenPathString.equals(TimeTrackerService.this.gitRepoPath)) {
										LOG.log(Level.WARNING, "User selected same hooks directory as .git, probably meant that/hooks");
										chosenPathString = chosenPath.resolve("hooks").toString();
									}
									TimeTrackerService.this.gitHooksPath = chosenPathString;
								} else {
									throw new AssertionError("not expected: "+result);
								}

								setGitIntegration(true);
							}
						}, ModalityState.NON_MODAL);
					}
				});
				Notifications.Bus.notify(notification, project());

				this.gitIntegration = false;
				return null;
			}
			default: {
				LOG.log(Level.SEVERE, "Invalid setupCommitHook result: " + result);
				return this.gitIntegration = false;
			}
		}
	}

	public boolean isPauseOtherTrackerInstances() {
		return pauseOtherTrackerInstances;
	}

	public synchronized void setPauseOtherTrackerInstances(boolean pauseOtherTrackerInstances) {
		this.pauseOtherTrackerInstances = pauseOtherTrackerInstances;
	}

	public long getNaggedAbout() {
		return naggedAbout;
	}

	public synchronized void setNaggedAbout(long naggedAbout) {
		this.naggedAbout = naggedAbout;
		nagAboutGitIntegrationIfNeeded();
	}

	private transient boolean naggedAboutGitIntegrationThisRun = false;

	private void nagAboutGitIntegrationIfNeeded() {
		if ((naggedAbout & TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION) == 0 && !gitIntegration && !naggedAboutGitIntegrationThisRun) {
			naggedAboutGitIntegrationThisRun = true;
			ApplicationManager.getApplication().invokeLater(() -> {
				final VirtualFile projectBaseDir = getProjectBaseDir(project());
				if (!gitIntegration && projectBaseDir != null && projectBaseDir.findChild(".git") != null) {
					final Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
							"Git repository detected",
							"Enable time tracker git integration?<br>You can change your mind later. Git integration will append time spent on commit into commit messages.",
							NotificationType.INFORMATION);

					notification.addAction(new AnAction("Yes") {
						@Override
						public void actionPerformed(@NotNull AnActionEvent e) {
							if (notification.isExpired()) {
								return;
							}
							notification.expire();

							setNaggedAbout(getNaggedAbout() | TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION);
							setGitIntegration(true);
						}
					});
					notification.addAction(new AnAction("No") {
						@Override
						public void actionPerformed(@NotNull AnActionEvent e) {
							if (notification.isExpired()) {
								return;
							}
							notification.expire();

							setNaggedAbout(getNaggedAbout() | TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION);
						}
					});
					notification.notify(project());
				}
			}, ModalityState.NON_MODAL);
		}
	}

	@NotNull
	public TimePattern getIdeTimePattern() {
		// widget sometimes manages to ask for the pattern before initialization, so we return dummy value
		final TimePattern pattern = this.ideTimePattern;
		return pattern == null ? NOTIFICATION_TIME_FORMATTING : pattern;
	}

	public synchronized void setIdeTimePattern(@NotNull TimePattern ideTimePattern) {
		this.ideTimePattern = ideTimePattern;
		repaintWidget(true);
	}

	@NotNull
	public TimePattern getGitTimePattern() {
		final TimePattern pattern = this.gitTimePattern;
		return pattern == null ? NOTIFICATION_TIME_FORMATTING : pattern;
	}

	public synchronized void setGitTimePattern(@NotNull TimePattern gitTimePattern) {
		this.gitTimePattern = gitTimePattern;
		updateGitTime(0);
	}

	private void updateGitTime(long seconds) {
		final GitIntegration gitIntegrationComponent = this.gitIntegrationComponent;
		final TimePattern gitTimePattern = this.gitTimePattern;
		if (gitIntegration && gitIntegrationComponent != null && gitTimePattern != null) {
			gitIntegrationComponent.updateVersionTimeFile(seconds, gitTimePattern);
		}
	}

	@Nullable
	private Project project() {
		final Project project = this.project;
		if (project.isDisposed()) {
			return null;
		}
		return project;
	}

	private void repaintWidget(boolean relayout) {
		final TimeTrackerWidget widget = this.widget;
		if (widget != null) {
			UIUtil.invokeLaterIfNeeded(() -> {
				widget.repaint();
				if (relayout) {
					widget.revalidate();
				}
			});
		}
	}

	@Override
	public void noStateLoaded() {
		if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "noStateLoaded() "+this);
		loadState(DefaultSettingsService.getDefaultState());
	}

	public void loadStateDefaults(@NotNull TimeTrackerPersistentState defaults) {
		final TimeTrackerPersistentState modifiedState = getState();
		modifiedState.setDefaultsFrom(defaults);
		loadState(modifiedState);
	}

	@Override
	public void loadState(@NotNull TimeTrackerPersistentState state) {
		if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "loadState() "+this);
		ApplicationManager.getApplication().invokeLater(() -> {
			synchronized (this) {
				this.totalTimeMs = state.totalTimeSeconds * 1000L;
				setAutoStart(state.autoStart);
				setIdleThresholdMs(state.idleThresholdMs);
				setAutoCountIdleSeconds(state.autoCountIdleSeconds);
				setStopWhenIdleRatherThanPausing(state.stopWhenIdleRatherThanPausing);
				setPauseOtherTrackerInstances(state.pauseOtherTrackerInstances);
				setNaggedAbout(state.naggedAbout);
				setIdeTimePattern(TimePattern.parse(state.ideTimePattern));

				gitIntegration = false;//Otherwise setGitTimePattern triggers update
				setGitTimePattern(TimePattern.parse(state.gitTimePattern));
				this.gitRepoPath = state.gitRepoPath;
				this.gitHooksPath = state.gitHooksPath;
				setGitIntegration(state.gitIntegration);
			}
			repaintWidget(true);
		});
	}

	@NotNull
	@Override
	public synchronized TimeTrackerPersistentState getState() {
		if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "getState() "+this);
		final TimeTrackerPersistentState result = new TimeTrackerPersistentState();
		result.totalTimeSeconds = msToS(totalTimeMs);

		result.autoStart = autoStart;
		result.idleThresholdMs = idleThresholdMs;
		result.autoCountIdleSeconds = autoCountIdleSeconds;
		result.stopWhenIdleRatherThanPausing = stopWhenIdleRatherThanPausing;
		result.gitIntegration = gitIntegration;
		result.gitRepoPath = gitRepoPath;
		result.gitHooksPath = gitHooksPath;
		result.pauseOtherTrackerInstances = pauseOtherTrackerInstances;

		result.naggedAbout = naggedAbout;

		result.ideTimePattern = ideTimePattern != null ? ideTimePattern.source : TimeTrackerPersistentState.DEFAULT_IDE_TIME_PATTERN;
		result.gitTimePattern = gitTimePattern != null ? gitTimePattern.source : TimeTrackerPersistentState.DEFAULT_GIT_TIME_PATTERN;
		return result;
	}

	/** User did something, this resets the idle timer and restarts counting, if applicable. */
	public void notifyUserNotIdle() {
		final long now = System.currentTimeMillis();
		this.lastActivityMs = now;
		if (status == TimeTrackingStatus.IDLE) {
			synchronized (this) {
				if (status == TimeTrackingStatus.IDLE) {
					setStatus(TimeTrackingStatus.RUNNING, now);
				}
			}
		}
	}

	@Override
	public void dispose() {
		if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "disposeComponent() "+this);
		ALL_OPENED_TRACKERS.remove(this);

		setStatus(TimeTrackingStatus.STOPPED);
	}

	@Override
	public String toString() {
		return "TTC("+ project +")@"+System.identityHashCode(this);
	}
}
