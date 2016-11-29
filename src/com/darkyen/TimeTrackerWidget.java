package com.darkyen;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public final class TimeTrackerWidget extends JButton implements CustomStatusBarWidget, AWTEventListener {

    private TimeTrackerState state = new TimeTrackerState();

    private boolean running = false;
    private long startedAtMs = 0;

    private boolean idle = false;
    private long lastActivityAtMs = System.currentTimeMillis();

    private ScheduledFuture<?> ticker;

    private DocumentListener autoStartDocumentListener = null;

    TimeTrackerWidget() {
        addActionListener(e -> setRunning(!running));
        setBorder(StatusBarWidget.WidgetBorder.INSTANCE);
        setOpaque(false);
        setFocusable(false);
    }

    void setState(TimeTrackerState state) {
        this.state = state;
        setupAutoStartDocumentListener(state.autoStart);
    }

    synchronized TimeTrackerState getState() {
        final long runningForSeconds = runningForSeconds();
        if (runningForSeconds > 0) {
            state.totalTimeSeconds += runningForSeconds;
            startedAtMs += runningForSeconds * 1000;
        }
        return state;
    }

    private long runningForSeconds() {
        if (!running) {
            return 0;
        } else {
            return Math.max(System.currentTimeMillis() - startedAtMs, 0) / 1000;
        }
    }

    private synchronized void setRunning(boolean running) {
        if (!this.running && running) {
            this.idle = false;
            this.running = true;
            this.startedAtMs = System.currentTimeMillis();

            if (ticker != null) {
                ticker.cancel(false);
            }
            ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(() -> UIUtil.invokeLaterIfNeeded(() -> {
                final long now = System.currentTimeMillis();
                if (now - lastActivityAtMs > state.idleThresholdMs) {
                    if (this.running) {
                        setRunning(false);
                        idle = true;
                    }
                }
                repaint();
            }), 1, 1, TimeUnit.SECONDS);
        } else if(this.running && !running) {
            state.totalTimeSeconds += runningForSeconds();
            this.running = false;

            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
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
    }

    private void setupAutoStartDocumentListener(boolean enabled) {
        final EditorEventMulticaster editorEventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        if (autoStartDocumentListener != null) {
            editorEventMulticaster.removeDocumentListener(autoStartDocumentListener);
            autoStartDocumentListener = null;
        }
        if (enabled) {
            editorEventMulticaster.addDocumentListener(autoStartDocumentListener = new DocumentAdapter() {
                @Override
                public void documentChanged(DocumentEvent e) {
                    if (!running) {
                        setRunning(true);
                    }
                }
            });
        }
    }

    @Override
    public void dispose() {
        setupAutoStartDocumentListener(false);
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        setRunning(false);
    }

    private static final Color COLOR_OFF = new JBColor(new Color(189, 0, 16), new Color(128, 0, 0));
    private static final Color COLOR_ON = new JBColor(new Color(28, 152, 19), new Color(56, 113, 41));
    private static final Color COLOR_IDLE = new JBColor(new Color(200, 164, 23), new Color(163, 112, 17));

    @Override
    public void paintComponent(final Graphics g) {
        long result;
        synchronized (this) {
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
        final long days = duration.toDays();
        if(days != 0) {
            found = true;
            sb.append(days).append(" day");
            if (days != 1) {
                sb.append("s");
            }
        }
        final long hours = duration.toHours() % 24;
        if(found || hours != 0) {
            if(found) {
                sb.append(" ");
            }
            found = true;
            sb.append(hours).append(" hour");
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
        lastActivityAtMs = System.currentTimeMillis();
        if (idle) {
            idle = false;
            setRunning(true);
        }
    }
}
