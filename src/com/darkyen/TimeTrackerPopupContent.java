package com.darkyen;

import javax.swing.*;
import java.awt.*;

/**
 *
 */
final class TimeTrackerPopupContent extends JPanel {

	TimeTrackerPopupContent(TimeTrackerComponent component) {
		super(new GridLayout(0, 2, 4, 0));

		final int insetLR = 10;
		final int insetTB = 5;
		this.setBorder(BorderFactory.createEmptyBorder(insetTB, insetLR, insetTB, insetLR));

		{
			this.add(new JLabel("Idle threshold (sec):"));
			final JSpinner idleThresholdSpinner = new JSpinner(new SpinnerNumberModel(component.getIdleThresholdMs() / 1000, 10, Integer.MAX_VALUE, 10));
			this.add(idleThresholdSpinner);
			idleThresholdSpinner.addChangeListener(ce ->
					component.setIdleThresholdMs(((Number) idleThresholdSpinner.getValue()).longValue() * 1000));
		}

		{
			this.add(new JLabel("Auto start on typing:"));
			final JCheckBox autoStartCheckBox = new JCheckBox();
			autoStartCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			autoStartCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			autoStartCheckBox.setSelected(component.isAutoStart());
			this.add(autoStartCheckBox);
			autoStartCheckBox.addActionListener(al -> {
				component.setAutoStart(autoStartCheckBox.isSelected());
			});
		}

		{
			this.add(new JLabel("Pause other IDE windows when this one activates:"));
			final JCheckBox autoPauseCheckBox = new JCheckBox();
			autoPauseCheckBox.setSelected(component.isPauseOtherTrackerInstances());
			autoPauseCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			autoPauseCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			this.add(autoPauseCheckBox);
			autoPauseCheckBox.addActionListener(al -> {
				component.setPauseOtherTrackerInstances(autoPauseCheckBox.isSelected());
			});
		}

		{
			this.add(new JLabel("Inject work time into Git commits:"));
			final JCheckBox gitIntegrationCheckBox = new JCheckBox();
			gitIntegrationCheckBox.setSelected(component.isGitIntegration());
			gitIntegrationCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
			gitIntegrationCheckBox.setVerticalAlignment(SwingConstants.CENTER);
			this.add(gitIntegrationCheckBox);
			gitIntegrationCheckBox.addActionListener(al -> {
				gitIntegrationCheckBox.setSelected(component.setGitIntegration(gitIntegrationCheckBox.isSelected()));
			});
		}

		{
			final JButton timeResetButton = new JButton("Reset time");
			timeResetButton.setToolTipText("Completely reset tracked time, including git time, if enabled");
			timeResetButton.addActionListener(e1 -> component.addOrResetTotalTimeMs(TimeTrackerComponent.RESET_TIME_TO_ZERO));
			this.add(timeResetButton);


			{// +time buttons
				final int[] timesSec = {-3600, -60 * 5, -30, 30, 60 * 5, 3600};
				final String[] labels = {"-1h", "-5m", "-30s", "+30s", "+5m", "+1h"};

				final Box timeButtons = Box.createHorizontalBox();
				for (int i = 0; i < labels.length; i++) {
					final int timeChange = timesSec[i];
					final JButton timeButton = new JButton(labels[i]);
					timeButton.addActionListener(e1 -> component.addOrResetTotalTimeMs(timeChange * 1000));
					timeButtons.add(timeButton);
				}
				this.add(timeButtons);
			}
		}
	}
}
