package com.darkyen;

import com.intellij.ide.ui.UISettings;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManagerAdapter;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ComponentPopupBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public final class TimeTrackerWidget extends JButton implements CustomStatusBarWidget, AWTEventListener {

    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Darkyenus Time Tracker", NotificationDisplayType.BALLOON, false, null, EmptyIcon.ICON_0);

    private final Project project;
    private final GitIntegration gitIntegration;

    private TimeTrackerState state = new TimeTrackerState();

    private boolean running = false;
    private long startedAtMs = 0;

    private boolean idle = false;
    private long lastActivityAtMs = System.currentTimeMillis();
    private long lastTickAt;

    private ScheduledFuture<?> ticker;
    private long tickTooLongMs;

    private DocumentListener autoStartDocumentListener = null;
    private FileDocumentManagerListener saveDocumentListener = null;

    private static final Object ALL_OPENED_TRACKERS_LOCK = new Object();
    private static final java.util.List<TimeTrackerWidget> ALL_OPENED_TRACKERS = Collections.synchronizedList(new ArrayList<>());

    TimeTrackerWidget(Project project) {
        this.project = project;
        this.gitIntegration = new GitIntegration(project);
        addActionListener(e -> setRunning(!running));
        setBorder(StatusBarWidget.WidgetBorder.INSTANCE);
        setOpaque(false);
        setFocusable(false);

        setupAutoStartDocumentListener(state.autoStart);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    final JPanel root = new JPanel();
                    root.setLayout(new GridLayout(0, 2, 4, 0));
                    final int insetLR = 10;
                    final int insetTB = 5;
                    root.setBorder(BorderFactory.createEmptyBorder(insetTB, insetLR, insetTB, insetLR));

                    final JLabel idleThresholdLabel = new JLabel("Idle threshold (sec):");
                    final JSpinner idleThresholdSpinner = new JSpinner(new SpinnerNumberModel(state.idleThresholdMs / 1000, 10, Integer.MAX_VALUE, 10));
                    root.add(idleThresholdLabel);
                    root.add(idleThresholdSpinner);
                    idleThresholdSpinner.addChangeListener(ce -> state.idleThresholdMs = ((Number)idleThresholdSpinner.getValue()).longValue() * 1000);

                    final JLabel autoStartLabel = new JLabel("Auto start on typing:");
                    final JCheckBox autoStartCheckBox = new JCheckBox();
                    autoStartCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
                    autoStartCheckBox.setVerticalAlignment(SwingConstants.CENTER);
                    autoStartCheckBox.setSelected(state.autoStart);
                    root.add(autoStartLabel);
                    root.add(autoStartCheckBox);
                    autoStartCheckBox.addActionListener(al -> {
                        state.autoStart = autoStartCheckBox.isSelected();
                        setupAutoStartDocumentListener(state.autoStart);
                    });

                    final JLabel gitIntegrationLabel = new JLabel("Inject work time into Git commits:");
                    final JCheckBox gitIntegrationCheckBox = new JCheckBox();
                    gitIntegrationCheckBox.setSelected(state.gitIntegration);
                    gitIntegrationCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
                    gitIntegrationCheckBox.setVerticalAlignment(SwingConstants.CENTER);
                    root.add(gitIntegrationLabel);
                    root.add(gitIntegrationCheckBox);
                    gitIntegrationCheckBox.addActionListener(al -> {
                        final boolean enable = gitIntegrationCheckBox.isSelected();
                        if(setupGitIntegration(enable)) {
                            state.gitIntegration = enable;
                        } else {
                            gitIntegrationCheckBox.setSelected(state.gitIntegration);
                        }
                    });

                    final JLabel autoPauseLabel = new JLabel("Pause others when this activates:");
                    final JCheckBox autoPauseCheckBox = new JCheckBox();
                    autoPauseCheckBox.setSelected(state.pauseOtherTrackerInstances);
                    autoPauseCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
                    autoPauseCheckBox.setVerticalAlignment(SwingConstants.CENTER);
                    root.add(autoPauseLabel);
                    root.add(autoPauseCheckBox);
                    autoPauseCheckBox.addActionListener(al -> state.pauseOtherTrackerInstances = autoPauseCheckBox.isSelected());

                    final JButton timeResetButton = new JButton("Reset time");
                    timeResetButton.setToolTipText("Completely reset tracked time, including git time, if enabled");
                    timeResetButton.addActionListener(e1 -> {
                        final boolean previouslyRunning = TimeTrackerWidget.this.running;
                        setRunning(false);
                        if (state.gitIntegration) {
                            gitIntegration.updateVersionTimeFile(GitIntegration.RESET_TIME_TO_ZERO);
                        }
                        state.totalTimeSeconds = 0;
                        repaint();
                        setRunning(previouslyRunning);
                    });
                    root.add(timeResetButton);

                    {// +time buttons
                        final long[] timesSec = {-3600, -60*5, -30, 30, 60*5, 3600};
                        final String[] labels = {"-1h", "-5m", "-30s", "+30s", "+5m", "+1h"};

                        final JPanel timeButtonsPanel = new JPanel();
                        timeButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
                        timeButtonsPanel.setLayout(new BoxLayout(timeButtonsPanel, BoxLayout.LINE_AXIS));
                        for (int i = 0; i < labels.length; i++) {
                            final long timeChange = timesSec[i];
                            final JButton timeButton = new JButton(labels[i]);
                            timeButton.addActionListener(e1 -> {
                                final boolean previouslyRunning = TimeTrackerWidget.this.running;
                                setRunning(false);
                                if (state.gitIntegration) {
                                    gitIntegration.updateVersionTimeFile(timeChange);
                                }
                                state.totalTimeSeconds = Math.max(0, state.totalTimeSeconds + timeChange);
                                repaint();
                                setRunning(previouslyRunning);
                            });
                            timeButtonsPanel.add(timeButton);
                        }
                        root.add(timeButtonsPanel);
                    }

                    final ComponentPopupBuilder popupBuilder = JBPopupFactory.getInstance().createComponentPopupBuilder(root, null);
                    popupBuilder.setCancelOnClickOutside(true);
                    popupBuilder.setFocusable(true);
                    popupBuilder.setShowBorder(true);
                    popupBuilder.setShowShadow(true);
                    final JBPopup popup = popupBuilder.createPopup();

                    final Rectangle visibleRect = TimeTrackerWidget.this.getVisibleRect();
                    final Dimension preferredSize = root.getPreferredSize();
                    final RelativePoint point = new RelativePoint(TimeTrackerWidget.this, new Point(visibleRect.x+visibleRect.width - preferredSize.width, visibleRect.y - (preferredSize.height + 15)));
                    popup.show(point);
                }
            }
        });
    }

    void setState(TimeTrackerState state) {
        repaint();
        this.state = state;
        setupAutoStartDocumentListener(state.autoStart);
        setupGitIntegration(state.gitIntegration);

        if ((state.naggedAbout & TimeTrackerState.NAGGED_ABOUT_GIT_INTEGRATION) == 0
                && !state.gitIntegration && project.getBaseDir().findChild(".git") != null) {
            Notifications.Bus.notify(NOTIFICATION_GROUP.createNotification(
                    "Git repository detected",
                    "Enable time tracker git integration?<br><a href=\"yes\">Yes</a> <a href=\"no\">No</a><br>You can change your mind later. Git integration will append time spent on commit into commit messages.",
                    NotificationType.INFORMATION,
                    (n, event) -> {
                        if ("yes".equals(event.getDescription())) {
                            if (!state.gitIntegration && setupGitIntegration(true)) {
                                state.gitIntegration = true;
                            }
                        } else if (!"no".equals(event.getDescription())) {
                            return;
                        }
                        n.expire();
                        state.naggedAbout |= TimeTrackerState.NAGGED_ABOUT_GIT_INTEGRATION;

                    }), project);
        }
    }

    private void foldRunningTime() {
        final long runningForSeconds = runningForSeconds();
        if (runningForSeconds > 0) {
            state.totalTimeSeconds += runningForSeconds;
            addVersionTime(runningForSeconds);
            startedAtMs += runningForSeconds * 1000;
        }
    }

    synchronized TimeTrackerState getState() {
        checkForTimeJump(false);
        foldRunningTime();
        return state;
    }

    private long runningForSeconds() {
        if (!running) {
            return 0;
        } else {
            return Math.max(System.currentTimeMillis() - startedAtMs, 0) / 1000;
        }
    }

    private synchronized void checkForTimeJump(boolean stop) {
        final long now = System.currentTimeMillis();
        final long sinceLastTick = now - lastTickAt;
        if (this.running && sinceLastTick > tickTooLongMs) {
            lastTickAt = now;
            final long frozenForSec = (sinceLastTick - (tickTooLongMs / 2)) / 1000;
            foldRunningTime();
            if (stop) {
                setRunning(false);
                idle = true;
            }
            state.totalTimeSeconds -= frozenForSec;
            addVersionTime(-frozenForSec);

            final Notification notification = NOTIFICATION_GROUP.createNotification(
                    "Hibernation or freeze detected",
                    "For " + formatDuration(sinceLastTick / 1000) + ".<br>This time is not counted. <a href=\"revert\">Count anyway</a>.",
                    NotificationType.WARNING,
                    (n, event) -> {
                        if ("revert".equals(event.getDescription())) {
                            n.expire();
                            state.totalTimeSeconds += frozenForSec;
                            addVersionTime(frozenForSec);
                            repaint();
                        }
                    });
            Notifications.Bus.notify(notification, project);
        }
    }

    private synchronized void setRunning(boolean running) {
        if (!this.running && running) {
            if (state.pauseOtherTrackerInstances) {
                synchronized (ALL_OPENED_TRACKERS_LOCK) {
                    for (TimeTrackerWidget tracker : ALL_OPENED_TRACKERS) {
                        if (tracker != this && tracker.running) {
                            tracker.setRunning(false);
                            tracker.idle = true;
                        }
                    }
                }
            }
            repaint();
            this.idle = false;
            this.running = true;
            this.startedAtMs = System.currentTimeMillis();

            if (ticker != null) {
                ticker.cancel(false);
            }
            lastTickAt = System.currentTimeMillis();
            final long tickDelay = 1;
            final TimeUnit tickDelayUnit = TimeUnit.SECONDS;
            tickTooLongMs = tickDelayUnit.toMillis(tickDelay) * 20;//20 sec is too long
            ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(() -> UIUtil.invokeLaterIfNeeded(() -> {
                checkForTimeJump(true);
                lastTickAt = System.currentTimeMillis();
                if (System.currentTimeMillis() - lastActivityAtMs > state.idleThresholdMs) {
                    if (this.running) {
                        setRunning(false);
                        idle = true;
                    }
                }
                repaint();
            }), tickDelay, tickDelay, tickDelayUnit);
        } else if(this.running && !running) {
            checkForTimeJump(false);
            final long runningForSeconds = runningForSeconds();
            state.totalTimeSeconds += runningForSeconds;
            addVersionTime(runningForSeconds);
            this.running = false;

            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
            repaint();
        }
    }

    @NotNull
    @Override
    public String ID() {
        return "com.darkyen.DarkyenusTimeTracker";
    }

    @Nullable
    @Override
    public WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return null;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,
                AWTEvent.KEY_EVENT_MASK |
                        AWTEvent.MOUSE_EVENT_MASK |
                        AWTEvent.MOUSE_MOTION_EVENT_MASK
        );
        saveDocumentListener = new FileDocumentManagerAdapter() {
            @Override
            public void beforeAllDocumentsSaving() {
                foldRunningTime();
            }

            @Override
            public void beforeDocumentSaving(@NotNull Document document) {
                foldRunningTime();
            }
        };
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME).registerExtension(saveDocumentListener);

        synchronized (ALL_OPENED_TRACKERS_LOCK) {
            ALL_OPENED_TRACKERS.add(this);
        }
    }

    private void setupAutoStartDocumentListener(boolean enabled) {
        final EditorEventMulticaster editorEventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        if (autoStartDocumentListener != null) {
            editorEventMulticaster.removeDocumentListener(autoStartDocumentListener);
            autoStartDocumentListener = null;
        }
        if (enabled) {
            editorEventMulticaster.addDocumentListener(autoStartDocumentListener = new DocumentListener() {
                @Override
                public void documentChanged(DocumentEvent e) {
                    if (running) return;
                    //getSelectedTextEditor() must be run from event dispatch thread
                    EventQueue.invokeLater(() -> {
                        final Editor selectedTextEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                        if (selectedTextEditor == null) return;
                        if(e.getDocument().equals(selectedTextEditor.getDocument())) {
                            setRunning(true);
                        }
                    });
                }
            });
        }
    }

    private boolean setupGitIntegration(boolean enabled) {
        try {
            gitIntegration.setupCommitHook(enabled);
            return true;
        } catch (GitIntegration.CommitHookException ex) {
            final Notification notification = NOTIFICATION_GROUP.createNotification(
                    "Failed to "+(enabled ? "enable" : "disable")+" git integration",
                    ex.getMessage(),
                    NotificationType.WARNING, null);
            Notifications.Bus.notify(notification, project);
            return false;
        }
    }

    private void addVersionTime(long seconds) {
        if (state.gitIntegration) {
            gitIntegration.updateVersionTimeFile(seconds);
        }
    }

    @Override
    public void dispose() {
        setupAutoStartDocumentListener(false);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME).unregisterExtension(saveDocumentListener);
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        setRunning(false);

        synchronized (ALL_OPENED_TRACKERS_LOCK) {
            ALL_OPENED_TRACKERS.remove(this);
        }
    }

    private static final Color COLOR_OFF = new JBColor(new Color(189, 0, 16), new Color(128, 0, 0));
    private static final Color COLOR_ON = new JBColor(new Color(28, 152, 19), new Color(56, 113, 41));
    private static final Color COLOR_IDLE = new JBColor(new Color(200, 164, 23), new Color(163, 112, 17));

    @Override
    public void paintComponent(final Graphics g) {
        long result;
        synchronized (this) {
            checkForTimeJump(true);
            result = state.totalTimeSeconds + runningForSeconds();
        }
        final String info = formatDuration(result);

        final Dimension size = getSize();
        final Insets insets = getInsets();

        final int totalBarLength = size.width - insets.left - insets.right;
        final int barHeight = Math.max(size.height, getFont().getSize() + 2);
        final int yOffset = (size.height - barHeight) / 2;
        final int xOffset = insets.left;

        g.setColor(running ? COLOR_ON : (idle ? COLOR_IDLE : COLOR_OFF));
        g.fillRect(insets.left, insets.bottom, totalBarLength, size.height - insets.bottom - insets.top);

        final Color fg = getModel().isPressed() ? UIUtil.getLabelDisabledForeground() : JBColor.foreground();
        g.setColor(fg);
        UISettings.setupAntialiasing(g);
        g.setFont(getWidgetFont());
        final FontMetrics fontMetrics = g.getFontMetrics();
        final int infoWidth = fontMetrics.charsWidth(info.toCharArray(), 0, info.length());
        final int infoHeight = fontMetrics.getAscent();
        g.drawString(info, xOffset + (totalBarLength - infoWidth) / 2, yOffset + infoHeight + (barHeight - infoHeight) / 2 - 1);
    }

    private static String formatDuration(long secondDuration) {
        final Duration duration = Duration.ofSeconds(secondDuration);
        final StringBuilder sb = new StringBuilder();

        boolean found = false;
        boolean secondsRelevant = true;
        final long hours = duration.toHours();
        if(hours != 0) {
            found = true;
            secondsRelevant = false;
            sb.append(hours).append(" hr");
            if (hours != 1) {
                sb.append("s");
            }
        }
        final long minutes = duration.toMinutes() % 60;
        if(found || minutes != 0) {
            if(found) {
                sb.append(" ");
            }
            found = true;
            sb.append(minutes).append(" min");/*
            if (minutes != 1) {
                sb.append("s");
            }*/
        }
        if(secondsRelevant) {
            final long seconds = duration.getSeconds() % 60;
            {
                if(found) {
                    sb.append(" ");
                }
                sb.append(seconds).append(" sec");/*
            if (seconds != 1) {
                sb.append("s");
            }*/
            }
        }
        return sb.toString();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    private static Font getWidgetFont() {
        return JBUI.Fonts.label(11);
    }

    private static final String SAMPLE_STRING = formatDuration(999999999999L);
    @Override
    public Dimension getPreferredSize() {
        final Insets insets = getInsets();
        int width = getFontMetrics(getWidgetFont()).stringWidth(SAMPLE_STRING) + insets.left + insets.right + JBUI.scale(2);
        int height = getFontMetrics(getWidgetFont()).getHeight() + insets.top + insets.bottom + JBUI.scale(2);
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        final Component ultimateParent = UIUtil.findUltimateParent(this);
        final Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        // Un-idle this only if our ide window is active
        if (ApplicationManager.getApplication().isActive() && ultimateParent == activeWindow) {
            lastActivityAtMs = System.currentTimeMillis();
            if (idle) {
                idle = false;
                setRunning(true);
            }
        }
    }

    /** Identity equals, used in {@link TimeTrackerWidget#ALL_OPENED_TRACKERS}*/
    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }
}
