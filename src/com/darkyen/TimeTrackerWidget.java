package com.darkyen;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.popup.ComponentPopupBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Consumer;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 *
 */
public final class TimeTrackerWidget extends JButton implements CustomStatusBarWidget, AWTEventListener {

    public static final String ID = "com.darkyen.DarkyenusTimeTracker";

    @NotNull
    private final TimeTrackerService service;

    TimeTrackerWidget(@NotNull TimeTrackerService service) {
        this.service = service;
        setBorder(StatusBarWidget.WidgetBorder.INSTANCE);
        setOpaque(false);
        setFocusable(false);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2 || (e.isControlDown() && e.getButton() == MouseEvent.BUTTON1)) {
                    final TimeTrackerPopupContent content = new TimeTrackerPopupContent(service);

                    final ComponentPopupBuilder popupBuilder = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null);
                    popupBuilder.setCancelOnClickOutside(true);
                    popupBuilder.setFocusable(true);
                    popupBuilder.setRequestFocus(true);
                    popupBuilder.setShowBorder(true);
                    popupBuilder.setShowShadow(true);
                    final JBPopup popup = popupBuilder.createPopup();
                    content.popup = popup;

                    final Rectangle visibleRect = TimeTrackerWidget.this.getVisibleRect();
                    final Dimension preferredSize = content.getPreferredSize();
                    final RelativePoint point = new RelativePoint(TimeTrackerWidget.this, new Point(visibleRect.x+visibleRect.width - preferredSize.width, visibleRect.y - (preferredSize.height + 15)));
                    popup.show(point);

                    // Not sure if needed, but sometimes the popup is not clickable for some mysterious reason
                    // and it stopped happening when this was added
                    content.requestFocus();
                } else {
                    service.toggleRunning();
                }
            }
        });
    }

    @NotNull
    @Override
    public String ID() {
        return ID;
    }

    @Override
    public WidgetPresentation getPresentation() {
        return new WidgetPresentation() {
            @Override
            public String getTooltipText() {
                return "Darkyen's Time Tracker";
            }
            @Override
            public Consumer<MouseEvent> getClickConsumer() {
                return null;
            }
        };
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,
                AWTEvent.KEY_EVENT_MASK |
                        AWTEvent.MOUSE_EVENT_MASK |
                        AWTEvent.MOUSE_WHEEL_EVENT_MASK |
                        AWTEvent.MOUSE_MOTION_EVENT_MASK
        );
    }


    @Override
    public void dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }

    private static final Color COLOR_OFF = new JBColor(new Color(189, 0, 16), new Color(128, 0, 0));
    private static final Color COLOR_ON = new JBColor(new Color(28, 152, 19), new Color(56, 113, 41));
    private static final Color COLOR_IDLE = new JBColor(new Color(200, 164, 23), new Color(163, 112, 17));

    public static final TimePattern FULL_TIME_FORMATTING = TimePattern.parse("{{lw \"week\"s}} {{ld \"day\"s}} {{lh \"hour\"s}} {{lm \"minute\"s}} {{s \"second\"s}}");
    private int lastTimeToShow = -1;

    @Override
    public void paintComponent(final Graphics g) {
        final int timeToShow = service.getTotalTimeSeconds();
        final String info = service.getIdeTimePattern().secondsToString(timeToShow);

        if (timeToShow != lastTimeToShow) {
            lastTimeToShow = timeToShow;
            setToolTipText("<html>" + FULL_TIME_FORMATTING.secondsToString(timeToShow)+"<br/>Middle click or Ctrl + Left click to open settings</html>");
        }

        final Dimension size = getSize();
        final Insets insets = getInsets();

        final int totalBarLength = size.width - insets.left - insets.right;
        final int barHeight = Math.max(size.height, getFont().getSize() + 2);
        final int yOffset = (size.height - barHeight) / 2;
        final int xOffset = insets.left;

        switch (service.getStatus()) {
            case RUNNING:
                g.setColor(COLOR_ON);
                break;
            case IDLE:
                g.setColor(COLOR_IDLE);
                break;
            case STOPPED:
                g.setColor(COLOR_OFF);
                break;
        }
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

    @Override
    public JComponent getComponent() {
        return this;
    }

    private static Font getWidgetFont() {
        return JBUI.Fonts.label(11);
    }

    private TimePattern getPreferredSize_lastPattern = null;
    private Font getPreferredSize_lastFont = null;
    private int getPreferredSize_lastWidth = -1;

    @Override
    public Dimension getPreferredSize() {
        final Font widgetFont = getWidgetFont();
        final FontMetrics fontMetrics = getFontMetrics(widgetFont);
        final TimePattern pattern = service.getIdeTimePattern();
        final int stringWidth;

        if (widgetFont.equals(getPreferredSize_lastFont) && pattern.equals(getPreferredSize_lastPattern)) {
            stringWidth = getPreferredSize_lastWidth;
        } else {
            int maxWidth = 0;
            // Size may decrease with growing time, so we try different second boundaries
            for (int seconds : new int[]{
                    60,
                    60 * 60,
                    60 * 60 * 24,
                    60 * 60 * 24 * 7,
                    1999999999
            }) {
                maxWidth = Math.max(maxWidth, fontMetrics.stringWidth(pattern.secondsToString(seconds - 1)));
            }
            getPreferredSize_lastPattern = pattern;
            getPreferredSize_lastFont = widgetFont;
            getPreferredSize_lastWidth = maxWidth;
            stringWidth = maxWidth;
        }


        final Insets insets = getInsets();
        int width = stringWidth + insets.left + insets.right + JBUI.scale(2);
        int height = fontMetrics.getHeight() + insets.top + insets.bottom + JBUI.scale(2);
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
            service.notifyUserNotIdle();
        }
    }
}
