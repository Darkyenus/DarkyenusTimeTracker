package com.darkyen;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.darkyen.TimeTrackerComponent.msToS;

/**
 *
 */
final class TimeTrackerPopupContent extends Box {

	private static final Logger LOG = Logger.getLogger(TimeTrackerPopupContent.class.getName());

	JBPopup popup;

	TimeTrackerPopupContent(TimeTrackerComponent component) {
		super(BoxLayout.Y_AXIS);

		final int insetLR = 10;
		final int insetTB = 5;
		this.setBorder(BorderFactory.createEmptyBorder(insetTB, insetLR, insetTB, insetLR));

		final JPanel optionsPanel = new JPanel(new GridLayout(0, 2, 4, 0));
		this.add(optionsPanel);

		{
			final String[] modes = new String[] {
					"Pause after (sec):",
					"Stop after (sec):"
			};
			final ComboBox<String> modeComboBox = new ComboBox<>(modes);
			modeComboBox.setSelectedIndex(component.isStopWhenIdleRatherThanPausing() ? 1 : 0);
			modeComboBox.addActionListener(e -> {
				component.setStopWhenIdleRatherThanPausing(modeComboBox.getSelectedIndex() == 1);
			});
			modeComboBox.setAlignmentX(1f);
			optionsPanel.add(modeComboBox);

			final JSpinner idleThresholdSpinner = new JSpinner(new SpinnerNumberModel(msToS(component.getIdleThresholdMs()), 0, Integer.MAX_VALUE, 10));
			optionsPanel.add(idleThresholdSpinner);
			idleThresholdSpinner.addChangeListener(ce ->
					component.setIdleThresholdMs(((Number) idleThresholdSpinner.getValue()).longValue() * 1000));
		}

		{
			optionsPanel.add(new JLabel("Auto-count pauses shorter than (sec):", JLabel.RIGHT));
			final JSpinner autoCountSpinner = new JSpinner(new SpinnerNumberModel(component.getAutoCountIdleSeconds(), 0, Integer.MAX_VALUE, 10));
			optionsPanel.add(autoCountSpinner);
			autoCountSpinner.addChangeListener(ce ->
					component.setAutoCountIdleSeconds(((Number) autoCountSpinner.getValue()).intValue()));
		}

		{
			optionsPanel.add(new JLabel("Auto start on typing:", JLabel.RIGHT));
			final JCheckBox autoStartCheckBox = new JCheckBox();
			autoStartCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			autoStartCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			autoStartCheckBox.setSelected(component.isAutoStart());
			optionsPanel.add(autoStartCheckBox);
			autoStartCheckBox.addActionListener(al -> {
				component.setAutoStart(autoStartCheckBox.isSelected());
			});
		}

		{
			optionsPanel.add(new JLabel("Pause other IDE windows when this one activates:", JLabel.RIGHT));
			final JCheckBox autoPauseCheckBox = new JCheckBox();
			autoPauseCheckBox.setSelected(component.isPauseOtherTrackerInstances());
			autoPauseCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			autoPauseCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			optionsPanel.add(autoPauseCheckBox);
			autoPauseCheckBox.addActionListener(al -> {
				component.setPauseOtherTrackerInstances(autoPauseCheckBox.isSelected());
			});
		}

		{
			optionsPanel.add(new JLabel("Inject work time into Git commits:", JLabel.RIGHT));
			final JCheckBox gitIntegrationCheckBox = new JCheckBox();
			gitIntegrationCheckBox.setSelected(component.isGitIntegration());
			gitIntegrationCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			gitIntegrationCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			optionsPanel.add(gitIntegrationCheckBox);
			gitIntegrationCheckBox.addActionListener(al -> {
				gitIntegrationCheckBox.setSelected(component.setGitIntegration(gitIntegrationCheckBox.isSelected()));
			});
		}

		{
			optionsPanel.add(new JLabel("Display time format:", JLabel.RIGHT));
			final TimePatternTextField patternField = new TimePatternTextField(
					component.getIdeTimePattern().source, component::setIdeTimePattern);
			optionsPanel.add(patternField);
		}

		{
			optionsPanel.add(new JLabel("Git time format:", JLabel.RIGHT));
			final TimePatternTextField patternField = new TimePatternTextField(
					component.getGitTimePattern().source, component::setGitTimePattern);
			optionsPanel.add(patternField);
		}

		{
			final Box timeButtons = Box.createHorizontalBox();

			final JButton timeResetButton = new JButton("Reset time");
			timeResetButton.setToolTipText("Completely reset tracked time, including git time, if enabled");
			timeResetButton.addActionListener(e1 -> component.addOrResetTotalTimeMs(TimeTrackerComponent.RESET_TIME_TO_ZERO));
			timeButtons.add(timeResetButton);
			timeButtons.add(Box.createHorizontalGlue());

			{// +time buttons
				final int[] timesSec = {-3600, -60 * 5, -30, 30, 60 * 5, 3600};
				final String[] labels = {"-1h", "-5m", "-30s", "+30s", "+5m", "+1h"};

				for (int i = 0; i < labels.length; i++) {
					final int timeChange = timesSec[i];
					final JButton timeButton = new JButton(labels[i]);
					timeButton.addActionListener(e1 -> {
						component.addOrResetTotalTimeMs(timeChange * 1000);
					});
					timeButtons.add(timeButton);
				}
			}
			this.add(timeButtons);
		}

		{
			final Box otherButtons = Box.createHorizontalBox();
			this.add(otherButtons);

			otherButtons.add(Box.createHorizontalGlue());

			final JButton loadDefaults = new JButton("Reset to defaults");
			loadDefaults.addActionListener(e1 -> {
				component.loadState(TimeTrackerDefaultSettingsComponent.instance().getState());
				popup.cancel();
			});
			otherButtons.add(loadDefaults);

			final JButton saveDefaults = new JButton("Save as defaults");
			saveDefaults.addActionListener(e1 -> {
				TimeTrackerDefaultSettingsComponent.instance().loadState(component.getState());
				popup.cancel();
			});
			otherButtons.add(saveDefaults);
		}
	}

	private static final class TimePatternTextField extends Box {

		private final JTextField patternField = new JTextField();
		private final JButton errorButton = new JButton(AllIcons.General.ExclMark);
		private final JButton infoButton = new JButton(AllIcons.General.Help_small);

		private int shownErrorIndex = -1;
		private final ArrayList<TimePattern.ParseError> errors = new ArrayList<>();

		private Consumer<TimePattern> onChanged;

		private static final JFrame helpWindow = new JFrame("Time Format Substitution Syntax");

		static {
			final JFrame helpWindow = TimePatternTextField.helpWindow;
			helpWindow.setResizable(true);
			helpWindow.setIconImage(null);
			helpWindow.setAlwaysOnTop(true);
			helpWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
			helpWindow.setType(Window.Type.UTILITY);

			String text;
			try {
				final InputStream stream = TimePatternTextField.class.getClassLoader()
						.getResourceAsStream("time-pattern-help.html");
				if (stream == null) {
					text = "Failed to load: resource is missing";
				} else {
					text = StreamUtil.readText(stream, StandardCharsets.UTF_8);
				}
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "Failed to load time pattern text", e);
				text = "Failed to load";
			}



			final JEditorPane area = new JEditorPane();
			area.setEditable(false);
			area.setContentType("text/html");
			area.setText(text);
			area.setMargin(JBUI.insets(10));
			area.addHyperlinkListener(e -> {
				if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
					return;
				}
				try {
					Desktop.getDesktop().browse(e.getURL().toURI());
				} catch (Exception ex) {
					LOG.log(Level.WARNING, "Failed to handle hyperlink: "+e, ex);
				}
			});

			final JScrollPane scroll = new JScrollPane(area, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			helpWindow.add(scroll);

			helpWindow.pack();
			helpWindow.setSize(700, 500);
		}

		public TimePatternTextField(String content, Consumer<TimePattern> onChanged) {
			super(BoxLayout.X_AXIS);

			add(patternField);
			add(errorButton);
			add(infoButton);

			final int buttonSize = patternField.getPreferredSize().height;
			final Dimension buttonDimension = new Dimension(buttonSize, buttonSize);
			errorButton.setPreferredSize(buttonDimension);
			infoButton.setPreferredSize(buttonDimension);

			patternField.setText(content);
			patternField.setEditable(true);
			refresh(content);
			this.onChanged = onChanged;// Do not call it with whatever we were given

			addChangeListener(patternField, this::refresh);

			errorButton.addActionListener(e -> {
				if (errors.isEmpty()) {
					return;
				}

				shownErrorIndex++;
				if (shownErrorIndex >= errors.size()) {
					shownErrorIndex = 0;
				}

				final TimePattern.ParseError error = errors.get(shownErrorIndex);
				patternField.requestFocusInWindow();
				patternField.select(error.index, error.index);

				final Insets foundInsets;
				{
					Insets insets = UIManager.getInsets("Balloon.error.textInsets");
					if (insets == null) {
						insets = JBUI.insets(3, 8, 8, 8);
					}
					foundInsets = insets;
				}

				BalloonBuilder builder = JBPopupFactory.getInstance().createBalloonBuilder(new JLabel(error.message))
						.setFillColor(MessageType.WARNING.getPopupBackground())
						.setAnimationCycle(Registry.intValue("ide.tooltip.animationCycle"))
						.setBlockClicksThroughBalloon(false)
						.setBorderInsets(foundInsets)
						.setShowCallout(true)
						.setHideOnKeyOutside(true)
						.setHideOnAction(true)
						.setRequestFocus(false);

				// "magic caret position" is valid only after repaint
				SwingUtilities.invokeLater(() -> {
					final Rectangle bounds = patternField.getBounds();
					Point position = patternField.getCaret().getMagicCaretPosition();
					if (position == null) {
						position = new Point(bounds.x + bounds.width / 2, 0);
					}
					// Show at the baseline of text (approximately), not at bottom border of element
					position.y = bounds.y + bounds.height - (bounds.height - patternField.getFontMetrics(patternField.getFont()).getHeight()) / 2 + foundInsets.top;

					builder.createBalloon().show(new RelativePoint(patternField, position), Balloon.Position.below);
				});
			});

			infoButton.addActionListener(e -> {
				final Rectangle bounds = helpWindow.getBounds();
				final Point location = getLocationOnScreen();
				location.y -= bounds.height + 10;

				helpWindow.setLocation(location);
				helpWindow.setVisible(true);
			});
		}

		private void refresh(String pattern) {
			errors.clear();
			final TimePattern result = TimePattern.parse(pattern, errors);
			if (onChanged != null) {
				onChanged.accept(result);
			}

			shownErrorIndex = -1;
			if (errors.isEmpty()) {
				errorButton.setVisible(false);
			} else {
				errorButton.setVisible(true);
			}
		}
	}

	// https://stackoverflow.com/a/27190162
	public static void addChangeListener(JTextComponent text, Consumer<String> changeListener) {
		final DocumentListener dl = new DocumentListener() {
			private int lastChange = 0, lastNotifiedChange = 0;

			@Override
			public void insertUpdate(DocumentEvent e) {
				changedUpdate(e);
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				changedUpdate(e);
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				lastChange++;
				SwingUtilities.invokeLater(() -> {
					if (lastNotifiedChange != lastChange) {
						lastNotifiedChange = lastChange;
						changeListener.accept(text.getText());
					}
				});
			}
		};
		text.addPropertyChangeListener("document", (PropertyChangeEvent e) -> {
			Document d1 = (Document)e.getOldValue();
			Document d2 = (Document)e.getNewValue();
			if (d1 != null) d1.removeDocumentListener(dl);
			if (d2 != null) d2.addDocumentListener(dl);
			dl.changedUpdate(null);
		});
		Document d = text.getDocument();
		if (d != null) d.addDocumentListener(dl);
	}
}
