package com.darkyen;

import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.SystemIndependent;

import java.awt.*;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every entry-point method of this class is to be synchronized.
 *
 * Each operation that needs to be on particular thread must do it itself.
 */
@State(name="DarkyenusTimeTracker", storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE)})
public final class TimeTrackerComponent implements ProjectComponent, PersistentStateComponent<TimeTrackerPersistentState> {

    private static final Logger LOG = Logger.getLogger(TimeTrackerComponent.class.getName());

    static final long RESET_TIME_TO_ZERO = Long.MIN_VALUE;

    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Darkyenus Time Tracker", NotificationDisplayType.BALLOON, false, null, EmptyIcon.ICON_0);
    public static final TimePattern NOTIFICATION_TIME_FORMATTING = TimePattern.parse("{{lw \"week\"s}} {{ld \"day\"s}} {{lh \"hour\"s}} {{lm \"minute\"s}} {{ts \"second\"s}}");

    private final Project project;
    private final GitIntegration gitIntegrationComponent;

    private TimeTrackerWidget widget;
    private StatusBar widgetStatusBar;


    private long totalTimeMs = 0;
    private Status status = Status.STOPPED;
    private long statusStartedMs = System.currentTimeMillis();
    private long lastTickMs = System.currentTimeMillis();
    private long lastActivityMs = System.currentTimeMillis();

    private boolean autoStart;
    private long idleThresholdMs;
    private int autoCountIdleSeconds;
    private boolean stopWhenIdleRatherThanPausing;
    private boolean pauseOtherTrackerInstances;

    private boolean gitIntegration;

    private long naggedAbout = 0;

    private TimePattern ideTimePattern;
    private TimePattern gitTimePattern;

    private ScheduledFuture<?> ticker;

    private static final long TICK_DELAY = 1;
    private static final TimeUnit TICK_DELAY_UNIT = TimeUnit.SECONDS;
    private static final long TICK_JUMP_DETECTION_THRESHOLD_MS = TICK_DELAY_UNIT.toMillis(TICK_DELAY * 20);

    private DocumentListener autoStartDocumentListener = null;
    private final FileDocumentManagerListener saveDocumentListener = new FileDocumentManagerListener() {
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
        public void beforeFileContentReload(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void fileWithNoDocumentChanged(@NotNull VirtualFile file) { }

        @Override
        public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void fileContentLoaded(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void unsavedDocumentsDropped() { }
    };

    private static final Set<TimeTrackerComponent> ALL_OPENED_TRACKERS = ContainerUtil.newConcurrentSet();

    @NotNull
    public Status getStatus() {
        return status;
    }

    public synchronized void toggleRunning() {
        switch (this.status) {
            case RUNNING:
                setStatus(Status.STOPPED);
                break;
            case STOPPED:
            case IDLE:
                setStatus(Status.RUNNING);
                break;
        }
    }

    private synchronized void tick() {
        if (status != Status.RUNNING) {
            LOG.warning("Tick when status is "+status);
            return;
        }

        final long now = System.currentTimeMillis();
        final long sinceLastTickMs = now - lastTickMs;
        final long sinceLastActivityMs = now - lastActivityMs;

        if (sinceLastTickMs > TICK_JUMP_DETECTION_THRESHOLD_MS) {
            final long lastValidTimeMs = lastTickMs + TICK_JUMP_DETECTION_THRESHOLD_MS;
            setStatus(stopWhenIdleRatherThanPausing ? Status.STOPPED : Status.IDLE, lastValidTimeMs);
        } else if (sinceLastActivityMs >= idleThresholdMs) {
            final long lastValidTimeMs = lastActivityMs + idleThresholdMs;
            setStatus(stopWhenIdleRatherThanPausing ? Status.STOPPED : Status.IDLE, lastValidTimeMs);
        }

        lastTickMs = now;
        repaintWidget(false);
    }

    private synchronized void otherComponentStarted() {
        if (status != Status.STOPPED) {
            setStatus(Status.IDLE);
        }
    }

    public synchronized void addOrResetTotalTimeMs(long milliseconds) {
        if (milliseconds == RESET_TIME_TO_ZERO) {
            totalTimeMs = 0L;
            statusStartedMs = System.currentTimeMillis();

            if (gitIntegration) {
                gitIntegrationComponent.updateVersionTimeFile(RESET_TIME_TO_ZERO, gitTimePattern);
            }
        } else {
            addTotalTimeMs(milliseconds);
        }
        repaintWidget(false);
    }

    private synchronized void addTotalTimeMs(long milliseconds) {
        totalTimeMs = Math.max(0L, totalTimeMs + milliseconds);

        if (gitIntegration) {
            gitIntegrationComponent.updateVersionTimeFile(msToS(milliseconds), gitTimePattern);
        }
    }

    private synchronized void saveTime() {
        if (status == Status.RUNNING) {
            final long now = System.currentTimeMillis();
            final long msInState = Math.max(0L, now - statusStartedMs);
            statusStartedMs = now;
            addTotalTimeMs(msInState);
        }
    }

    public synchronized void setStatus(@NotNull Status status) {
        setStatus(status, System.currentTimeMillis());
    }

    private void setStatus(final @NotNull Status status, final long now) {
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
                    final Notification notification = NOTIFICATION_GROUP.createNotification(
                            "Welcome back!",
                            "Gone for <b>" + NOTIFICATION_TIME_FORMATTING.millisecondsToString(msInState) + "</b>",
                            NotificationType.INFORMATION, null);

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
                break;
            }
        }

        this.statusStartedMs = now;
        this.lastTickMs = now;
        this.lastActivityMs = now;
        this.status = status;

        switch (status) {
            case RUNNING: {
                if (pauseOtherTrackerInstances) {
                    ALL_OPENED_TRACKERS.forEach(tracker -> {
                        if (tracker != this) {
                            tracker.otherComponentStarted();
                        }
                    });
                }

                ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(this::tick, TICK_DELAY, TICK_DELAY, TICK_DELAY_UNIT);
            }
                break;
        }

        repaintWidget(false);
    }

    public synchronized int getTotalTimeSeconds() {
        long resultMs = this.totalTimeMs;
        if (this.status == Status.RUNNING) {
            final long now = System.currentTimeMillis();
            resultMs += Math.max(0L, now - statusStartedMs);
        }

        return (int) msToS(resultMs);
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    private void updateAutoStartListener(boolean enabled) {
        final EditorEventMulticaster editorEventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        if (autoStartDocumentListener != null) {
            editorEventMulticaster.removeDocumentListener(autoStartDocumentListener);
            autoStartDocumentListener = null;
        }
        if (enabled) {
            editorEventMulticaster.addDocumentListener(autoStartDocumentListener = new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent e) {
                    if (getStatus() == Status.RUNNING) return;
                    //getSelectedTextEditor() must be run from event dispatch thread
                    EventQueue.invokeLater(() -> {
                        if (project.isDisposed()) return;

                        final Editor selectedTextEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                        if (selectedTextEditor == null) return;
                        if(e.getDocument().equals(selectedTextEditor.getDocument())) {
                            setStatus(Status.RUNNING);
                        }
                    });
                }
            });
        }
    }

    public synchronized void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
        updateAutoStartListener(autoStart);
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

    public synchronized boolean setGitIntegration(boolean enabled) {
        try {
            gitIntegrationComponent.setupCommitHook(enabled);
            this.gitIntegration = enabled;
            if (enabled) {
                // gitTimePattern may be null when we are just loading
                if (gitTimePattern != null) {
                    gitIntegrationComponent.updateVersionTimeFile(0, gitTimePattern);
                }
            } else {
                nagAboutGitIntegrationIfNeeded();
            }
            return enabled;
        } catch (GitIntegration.CommitHookException ex) {
            Notifications.Bus.notify(NOTIFICATION_GROUP.createNotification(
                    "Failed to "+(enabled ? "enable" : "disable")+" git integration",
                    ex.getMessage(),
                    NotificationType.WARNING, null), project);
            return this.gitIntegration = false;
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

    private void nagAboutGitIntegrationIfNeeded() {
        if ((naggedAbout & TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION) == 0 && !gitIntegration) {
            ApplicationManager.getApplication().invokeLater(() -> {
                final VirtualFile projectBaseDir = getProjectBaseDir(project);
                if (!gitIntegration && projectBaseDir != null && projectBaseDir.findChild(".git") != null) {
                    Notifications.Bus.notify(NOTIFICATION_GROUP.createNotification(
                            "Git repository detected",
                            "Enable time tracker git integration?<br><a href=\"yes\">Yes</a> <a href=\"no\">No</a><br>You can change your mind later. Git integration will append time spent on commit into commit messages.",
                            NotificationType.INFORMATION,
                            (n, event) -> {
                                if ("yes".equals(event.getDescription())) {
                                    setGitIntegration(true);
                                } else if (!"no".equals(event.getDescription())) {
                                    return;
                                }
                                n.expire();
                                setNaggedAbout(getNaggedAbout() | TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION);
                            }), project);
                }
            }, ModalityState.NON_MODAL);
        }
    }

    @NotNull
    public TimePattern getIdeTimePattern() {
        return ideTimePattern;
    }

    public synchronized void setIdeTimePattern(@NotNull TimePattern ideTimePattern) {
        this.ideTimePattern = ideTimePattern;
        repaintWidget(true);
    }

    @NotNull
    public TimePattern getGitTimePattern() {
        return gitTimePattern;
    }

    public synchronized void setGitTimePattern(@NotNull TimePattern gitTimePattern) {
        this.gitTimePattern = gitTimePattern;
        if (gitIntegration) {
            gitIntegrationComponent.updateVersionTimeFile(0, gitTimePattern);
        }
    }


    public TimeTrackerComponent(Project project) {
        this.project = project;
        this.gitIntegrationComponent = new GitIntegration(project);
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
        loadState(TimeTrackerDefaultSettingsComponent.instance().getState());
    }

    @Override
    public void loadState(@NotNull TimeTrackerPersistentState state) {
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
                setGitIntegration(state.gitIntegration);
            }
            repaintWidget(true);
        });
    }

    @Override
    public synchronized void initComponent() {
        ALL_OPENED_TRACKERS.add(this);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME)
                .registerExtension(saveDocumentListener);
    }

    @Override
    public synchronized void disposeComponent() {
        ALL_OPENED_TRACKERS.remove(this);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME)
                .unregisterExtension(saveDocumentListener);

        updateAutoStartListener(false);
        setStatus(Status.STOPPED);
    }

    @Override
    @NotNull
    public String getComponentName() {
        return "TimeTrackerComponent";
    }

    @Override
    public void projectOpened() {
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (this) {
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

                    final TimeTrackerWidget widget = new TimeTrackerWidget(this);
                    statusBar.addWidget(widget);
                    this.widget = widget;
                    this.widgetStatusBar = statusBar;
                }
            }
        });
    }

    @Override
    public void projectClosed() {
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (this) {
                if (widget != null && this.widgetStatusBar != null) {
                    widgetStatusBar.removeWidget(widget.ID());
                    this.widget = null;
                    this.widgetStatusBar = null;
                }
            }
        });
    }

    @NotNull
    @Override
    public synchronized TimeTrackerPersistentState getState() {
        final TimeTrackerPersistentState result = new TimeTrackerPersistentState();
        result.totalTimeSeconds = msToS(totalTimeMs);

        result.autoStart = autoStart;
        result.idleThresholdMs = idleThresholdMs;
        result.autoCountIdleSeconds = autoCountIdleSeconds;
        result.stopWhenIdleRatherThanPausing = stopWhenIdleRatherThanPausing;
        result.gitIntegration = gitIntegration;
        result.pauseOtherTrackerInstances = pauseOtherTrackerInstances;

        result.naggedAbout = naggedAbout;

        result.ideTimePattern = ideTimePattern.source;
        result.gitTimePattern = gitTimePattern.source;
        return result;
    }

    /** User did something, this resets the idle timer and restarts counting, if applicable. */
    public synchronized void notifyUserNotIdle() {
        final long now = System.currentTimeMillis();
        this.lastActivityMs = now;
        if (status == Status.IDLE) {
            setStatus(Status.RUNNING, now);
        }
    }

    public enum Status {
        RUNNING,
        IDLE,
        STOPPED
    }

    /** Rounded conversion of milliseconds to seconds. */
    public static long msToS(long ms) {
        return (ms + 500L) / 1000L;
    }

    public static VirtualFile getProjectBaseDir(Project project) {
        @SystemIndependent final String basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(basePath);
    }
}
