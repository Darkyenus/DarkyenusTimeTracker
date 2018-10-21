package com.darkyen;

import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
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
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDialog;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemIndependent;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.darkyen.GitIntegration.SetupCommitHookResult.GIT_DIR_NOT_FOUND;
import static com.darkyen.GitIntegration.SetupCommitHookResult.GIT_HOOKS_DIR_NOT_FOUND;

/**
 * Every entry-point method of this class is to be synchronized.
 *
 * Each operation that needs to be on particular thread must do it itself.
 */
@State(name="DarkyenusTimeTracker", storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE)})
public final class TimeTrackerComponent implements ProjectComponent, PersistentStateComponent<TimeTrackerPersistentState>, Disposable {

    private static final Logger LOG = Logger.getLogger(TimeTrackerComponent.class.getName());
    private static final boolean DEBUG_LIFECYCLE = false;

    static final long RESET_TIME_TO_ZERO = Long.MIN_VALUE;

    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Darkyenus Time Tracker", NotificationDisplayType.BALLOON, false, null, EmptyIcon.ICON_0);

    private static final NotificationGroup IDLE_NOTIFICATION_GROUP = new NotificationGroup("Darkyenus Time Tracker - Idle time", NotificationDisplayType.BALLOON, true, null, EmptyIcon.ICON_0);
    public static final TimePattern NOTIFICATION_TIME_FORMATTING = TimePattern.parse("{{lw \"week\"s}} {{ld \"day\"s}} {{lh \"hour\"s}} {{lm \"minute\"s}} {{ts \"second\"s}}");

    @Nullable
    private final Project _project;
    @Nullable
    private GitIntegration gitIntegrationComponent;

    @Nullable
    private TimeTrackerWidget widget;


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
            updateGitTime(RESET_TIME_TO_ZERO);
        } else {
            addTotalTimeMs(milliseconds);
        }
        repaintWidget(false);
    }

    private synchronized void addTotalTimeMs(long milliseconds) {
        totalTimeMs = Math.max(0L, totalTimeMs + milliseconds);
        updateGitTime(msToS(milliseconds));
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
                    final Project project = project();
                    if (project != null) {
                        final Notification notification = IDLE_NOTIFICATION_GROUP.createNotification(
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
                        final Project project = project();
                        if (project == null) return;

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
                    Notifications.Bus.notify(NOTIFICATION_GROUP.createNotification(
                            "Failed to enable git integration",
                            "Project is not on a local filesystem",
                            NotificationType.WARNING, null), project);
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
                Notifications.Bus.notify(NOTIFICATION_GROUP.createNotification(
                        "Failed to " + (enable ? "enable" : "disable") + " git integration",
                        result.message,
                        NotificationType.WARNING, null), project());
                return this.gitIntegration = false;
            }
            case GIT_DIR_NOT_FOUND:
            case GIT_HOOKS_DIR_NOT_FOUND: {
                final Notification notification = NOTIFICATION_GROUP.createNotification(
                        "Failed to enable git integration",
                        result == GIT_DIR_NOT_FOUND ? "'.git' directory not found" : "'.git/hooks' directory not found",
                        NotificationType.WARNING, null);
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

                            synchronized (TimeTrackerComponent.this) {
                                if (chosenPath.startsWith(projectBase)) {
                                    chosenPath = projectBase.relativize(chosenPath);
                                } else {
                                    chosenPath = chosenPath.toAbsolutePath();
                                }

                                TimeTrackerComponent.this.gitIntegrationComponent = null;

                                String chosenPathString = chosenPath.toString();
                                if (result == GIT_DIR_NOT_FOUND) {
                                    TimeTrackerComponent.this.gitRepoPath = chosenPathString;
                                    TimeTrackerComponent.this.gitHooksPath = chosenPath.resolve("hooks").toString();
                                } else if (result == GIT_HOOKS_DIR_NOT_FOUND) {
                                    if (chosenPathString.equals(TimeTrackerComponent.this.gitRepoPath)) {
                                        LOG.log(Level.WARNING, "User selected same hooks directory as .git, probably meant that/hooks");
                                        chosenPathString = chosenPath.resolve("hooks").toString();
                                    }
                                    TimeTrackerComponent.this.gitHooksPath = chosenPathString;
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

    private void nagAboutGitIntegrationIfNeeded() {
        if ((naggedAbout & TimeTrackerPersistentState.NAGGED_ABOUT_GIT_INTEGRATION) == 0 && !gitIntegration) {
            ApplicationManager.getApplication().invokeLater(() -> {
                final VirtualFile projectBaseDir = getProjectBaseDir(project());
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
                            }), project());
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

    public TimeTrackerComponent(@Nullable Project project) {
        this._project = project;
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "Instantiated "+this);
    }

    @Nullable
    private Project project() {
        final Project project = _project;
        if (project == null || project.isDisposed()) {
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
        loadState(TimeTrackerDefaultSettingsComponent.instance().getState());
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

    @Override
    public synchronized void initComponent() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "initComponent() "+this);
        ALL_OPENED_TRACKERS.add(this);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME)
                .registerExtension(saveDocumentListener);
    }

    @Override
    public synchronized void disposeComponent() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "disposeComponent() "+this);
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

    @Nullable
    private StatusBar widgetStatusBar() {
        final WindowManager windowManager = WindowManager.getInstance();
        if (windowManager == null) {
            return null;
        }
        final Project project = project();
        if (project == null) {
            return null;
        }
        return windowManager.getStatusBar(project);
    }

    @Override
    public void projectOpened() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "projectOpened() "+this);
        UIUtil.invokeLaterIfNeeded(() -> {
            TimeTrackerWidget widget;
            synchronized (this) {
                widget = this.widget;
                if (widget == null) {
                    this.widget = widget = new TimeTrackerWidget(this);
                }
            }

            final StatusBar statusBar = widgetStatusBar();
            if (statusBar != null) {
                // anchor: Memory is a widget that seems to be always present and always at the rightmost corner.
                // (it may be hidden, but it is there)
                // If that changes in the future, the widget will still be added correctly, but maybe in a different location
                statusBar.addWidget(widget, "before Memory", this);
            } else {
                LOG.log(Level.SEVERE, "Can't initialize time tracking widget, status bar is null");
            }
        });
    }

    @Override
    public void projectClosed() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "projectClosed() "+this);
        UIUtil.invokeLaterIfNeeded(() -> {
            final StatusBar statusBar = widgetStatusBar();
            if (statusBar != null) {
                // Usually null, but maybe can sometimes happen that the project is only closed and not disposed?
                statusBar.removeWidget(TimeTrackerWidget.ID);
            }
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
    public synchronized void notifyUserNotIdle() {
        final long now = System.currentTimeMillis();
        this.lastActivityMs = now;
        if (status == Status.IDLE) {
            setStatus(Status.RUNNING, now);
        }
    }

    @Override
    public String toString() {
        return "TTC("+_project+")@"+System.identityHashCode(this);
    }

    @Override
    public void dispose() {
        // Everything is implemented in disposeComponent()
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

    @Nullable
    public static VirtualFile getProjectBaseDir(@Nullable Project project) {
        if (project == null) {
            return null;
        }
        @SystemIndependent final String basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(basePath);
    }

    @Nullable
    private static Path convertToIOFile(@Nullable VirtualFile file) {
        if (file == null || !file.isInLocalFileSystem()) {
            return null;
        }

        // Based on LocalFileSystemBase.java
        String path = file.getPath();
        if (StringUtil.endsWithChar(path, ':') && path.length() == 2 && SystemInfo.isWindows) {
            path += "/";
        }

        return Paths.get(path);
    }
}
