
package com.darkyen;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.io.StreamUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
final class GitIntegration {

	private static final Logger LOG = Logger.getLogger("com.darkyen.GitIntegration");

	private static final String DTT_TIME_FILE_NAME = ".darkyenus_time_tracker_commit_time";

	@NotNull
	private final Path gitDirectory;
	@NotNull
	private final Path hooksDirectory;

	GitIntegration (@NotNull Path gitDirectory, @NotNull Path hooksDirectory) {
		this.gitDirectory = gitDirectory;
		this.hooksDirectory = hooksDirectory;
	}

	/*
	Git version time file format:
	Three lines:
	 1. Number of counted seconds (for tracking purposes of the plugin)
	 2. Formatted first number
	 3. Formatted 0 seconds (line 2 is reset to this when commit is made)
	 */

	void updateVersionTimeFile (long versionSeconds, @NotNull final TimePattern gitTimePattern) {
		final Application application = ApplicationManager.getApplication();
		application.invokeLater( () -> {
			if (!Files.isDirectory(gitDirectory)) {
				return;
			}

			final Path timeFile = gitDirectory.resolve(DTT_TIME_FILE_NAME);

			long existingSeconds = 0;
			{
				String countedSecondsLine = null;
				if (Files.exists(timeFile)) {
					try (final BufferedReader reader = new BufferedReader(Files
							.newBufferedReader(timeFile, StandardCharsets.UTF_8))) {
						countedSecondsLine = reader.readLine();
					} catch (IOException e) {
						LOG.log(Level.WARNING, "Failed to read git time file", e);
					}
				}

				try {
					if (countedSecondsLine != null) {
						existingSeconds = Long.parseLong(countedSecondsLine);
					}
				} catch (NumberFormatException ignored) {
					LOG.log(Level.WARNING, "Git time file did not contain only numbers: \"" + countedSecondsLine + "\"");
				}
			}

			final long newSeconds = versionSeconds == TimeTrackerComponent.RESET_TIME_TO_ZERO ? 0 : Math.max(0, existingSeconds + versionSeconds);

			try (final BufferedWriter out = Files.newBufferedWriter(timeFile, StandardCharsets.UTF_8)) {
				out.write(Long.toString(newSeconds));
				out.write('\n');
				out.write(gitTimePattern.secondsToString((int)newSeconds));
				out.write('\n');
				out.write(gitTimePattern.secondsToString(0));
				out.write('\n');
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "Error while writing git time file", e);
			}
		}, ModalityState.NON_MODAL);
	}

	private static byte[] _prepare_commit_message_hook_content = null;

	private static byte[] prepareCommitMessageHookContent () throws IOException {
		if (_prepare_commit_message_hook_content == null) {
			_prepare_commit_message_hook_content = StreamUtil.loadFromStream(GitIntegration.class.getResourceAsStream("/hooks/"
				+ PREPARE_COMMIT_MESSAGE_HOOK_NAME));
		}
		return _prepare_commit_message_hook_content;
	}

	private static final String PREPARE_COMMIT_MESSAGE_HOOK_NAME = "prepare-commit-msg";
	private static final String TIME_TRACKER_HOOK_IDENTIFIER = "#DarkyenusTimeTrackerHookScript";
	private static final String TIME_TRACKER_HOOK_IDENTIFIER_VERSIONED = TIME_TRACKER_HOOK_IDENTIFIER + "00005";

	private static void fillWithHookContent (Path hook) throws IOException {
		Files.write(hook, prepareCommitMessageHookContent());
		try {
			final HashSet<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(hook));
			permissions.add(PosixFilePermission.OTHERS_EXECUTE);
			permissions.add(PosixFilePermission.GROUP_EXECUTE);
			permissions.add(PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(hook, permissions);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Hook file at '"+hook+"' can't be marked as executable", e);
		}
	}

	SetupCommitHookResult setupCommitHook (boolean enable) {
		if (!Files.isDirectory(hooksDirectory)) {
			if (!enable) {
				return SetupCommitHookResult.SUCCESS;
			} else if (!Files.isDirectory(gitDirectory)) {
				return SetupCommitHookResult.GIT_DIR_NOT_FOUND;
			} else {
				return SetupCommitHookResult.GIT_HOOKS_DIR_NOT_FOUND;
			}
		}

		try {
			final Path hook = hooksDirectory.resolve(PREPARE_COMMIT_MESSAGE_HOOK_NAME);
			if (!Files.exists(hook)) {
				if (enable) {
					// Create new hook
					fillWithHookContent(hook);
					return SetupCommitHookResult.SUCCESS;
				} else {
					// All good, no hook is present
					return SetupCommitHookResult.SUCCESS;
				}
			} else {
				// Is it our hook?
				final String content = new String(Files.readAllBytes(hook), StandardCharsets.UTF_8);
				final boolean isTimeTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER);
				final boolean isRecentTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER_VERSIONED);
				if (enable) {
					if (isRecentTrackerHook) {
						// All good, recent hook is present
						// noinspection UnnecessaryReturnStatement
						return SetupCommitHookResult.SUCCESS;
					} else if (isTimeTrackerHook) {
						// There is our hook, but old, replace
						fillWithHookContent(hook);
						return SetupCommitHookResult.SUCCESS;
					} else {
						// This is not our hook!
						return SetupCommitHookResult.HOOK_ALREADY_EXISTS;
					}
				} else {
					if (isTimeTrackerHook) {
						// We are disabling and this is our hook, delete
						Files.deleteIfExists(hook);
						return SetupCommitHookResult.SUCCESS;
					} else {
						// We are disabling and this is not our hook, leave it alone
						return SetupCommitHookResult.SUCCESS;
					}
				}
			}
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Failed to setupCommitHook", ex);
			return SetupCommitHookResult.INTERNAL_ERROR;
		}
	}

	enum SetupCommitHookResult {
		SUCCESS(null),
		INTERNAL_ERROR("Internal error"),
		HOOK_ALREADY_EXISTS("There already is a " + PREPARE_COMMIT_MESSAGE_HOOK_NAME + " hook! Can't initialize."),
		GIT_DIR_NOT_FOUND("'.git/' not found in the project root directory"),
		GIT_HOOKS_DIR_NOT_FOUND(null);

		public final String message;

		SetupCommitHookResult(String message) {
			this.message = message;
		}
	}
}
