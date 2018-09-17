
package com.darkyen;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.darkyen.TimeTrackerComponent.getProjectBaseDir;

/**
 *
 */
final class GitIntegration {

	private static final Logger LOG = Logger.getLogger("com.darkyen.GitIntegration");

	private static final String DTT_TIME_FILE_NAME = ".darkyenus_time_tracker_commit_time";

	private final VirtualFile baseDir;

	GitIntegration (Project project) {
		this.baseDir = getProjectBaseDir(project);
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
			final VirtualFile child = baseDir.findChild(".git");
			if (child == null) return;

			final VirtualFile timeFile;
			try {
				timeFile = application.runWriteAction((ThrowableComputable<VirtualFile, IOException>) () -> child.findOrCreateChildData(GitIntegration.this, DTT_TIME_FILE_NAME));
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Failed to create git time VirtualFile", e);
				return;
			}

			final Long maybeExistingSeconds = application.runReadAction((Computable<Long>) () -> {
				timeFile.refresh(false, false);
				timeFile.setPreloadedContentHint(null);// Just to make sure
				timeFile.setBOM(null);

				final String countedSecondsLine;
				try (final BufferedReader reader = new BufferedReader(new InputStreamReader(timeFile.getInputStream(),
						StandardCharsets.UTF_8))) {
					countedSecondsLine = reader.readLine();
				} catch (IOException e) {
					LOG.log(Level.WARNING, "Failed to read git time file", e);
					return null;
				}

				try {
					if (countedSecondsLine != null) {
						return Long.parseLong(countedSecondsLine);
					}
				} catch (NumberFormatException ignored) {
					LOG.log(Level.WARNING, "Git time file did not contain only numbers: \"" + countedSecondsLine + "\"");
				}

				return null;
			});

			final long existingSeconds = maybeExistingSeconds == null ? 0 : maybeExistingSeconds;
			final long newSeconds = versionSeconds == TimeTrackerComponent.RESET_TIME_TO_ZERO ? 0 : Math.max(0, existingSeconds + versionSeconds);

			application.runWriteAction( () -> {
				try (final BufferedWriter out = new BufferedWriter(new OutputStreamWriter(timeFile.getOutputStream(GitIntegration.this), StandardCharsets.UTF_8))) {
					out.write(Long.toString(newSeconds));
					out.write('\n');
					out.write(gitTimePattern.secondsToString((int)newSeconds));
					out.write('\n');
					out.write(gitTimePattern.secondsToString(0));
					out.write('\n');
				} catch (IOException e) {
					LOG.log(Level.SEVERE, "Error while writing git time file", e);
				}
			});
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

	private VirtualFile getHookDirectory () {
		final VirtualFile git = baseDir.findChild(".git");
		if (git == null || !git.isDirectory()) return null;
		final VirtualFile hooks = git.findChild("hooks");
		if (hooks == null || !hooks.isDirectory()) return null;
		return hooks;
	}

	private static void fillWithHookContent (VirtualFile hook) throws IOException {
		hook.setBinaryContent(prepareCommitMessageHookContent());
		final String canonicalPath = hook.getCanonicalPath();
		final File file = canonicalPath == null ? null : new File(canonicalPath);
		if (file == null || !file.isFile()) {
			LOG.log(Level.WARNING, "Hook file can't be marked as executable, can't create java.io.File from it.");
		} else {
			if (!file.canExecute() && !file.setExecutable(true)) {
				LOG.log(Level.WARNING, "Hook file can't be marked as executable, can't mark as executable");
			}
		}
	}

	void setupCommitHook (boolean enable) throws CommitHookException {
		ApplicationManager.getApplication().runWriteAction(() -> {
			final VirtualFile hookDirectory = getHookDirectory();
			if (hookDirectory == null) {
				if (enable) {
					throw new CommitHookException("'.git/' not found in the project root directory");
				}
				return;
			}
			try {
				final VirtualFile hook = hookDirectory.findChild(PREPARE_COMMIT_MESSAGE_HOOK_NAME);
				if (hook == null) {
					if (enable) {
						// Create new hook
						final VirtualFile newHook = hookDirectory
								.createChildData(GitIntegration.class, PREPARE_COMMIT_MESSAGE_HOOK_NAME);
						fillWithHookContent(newHook);
					} else {
						// All good, no hook is present
						// noinspection UnnecessaryReturnStatement
						return;
					}
				} else {
					// Is it our hook?
					final String content = new String(hook.contentsToByteArray(), StandardCharsets.UTF_8);
					final boolean isTimeTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER);
					final boolean isRecentTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER_VERSIONED);
					if (enable) {
						if (isRecentTrackerHook) {
							// All good, recent hook is present
							// noinspection UnnecessaryReturnStatement
							return;
						} else if (isTimeTrackerHook) {
							// There is our hook, but old, replace
							fillWithHookContent(hook);
						} else {
							// This is not our hook!
							throw new CommitHookException("There already is a " + PREPARE_COMMIT_MESSAGE_HOOK_NAME + " hook! Can't initialize.");
						}
					} else {
						if (isTimeTrackerHook) {
							// We are disabling and this is our hook, delete
							hook.delete(GitIntegration.class);
						} else {
							// We are disabling and this is not our hook, leave it alone
							// noinspection UnnecessaryReturnStatement
							return;
						}
					}
				}
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Failed to setupCommitHook", ex);
				throw new CommitHookException("Internal error, failed to " + (enable ? "enable" : "disable"));
			}
		});
	}

	static final class CommitHookException extends RuntimeException {
		CommitHookException (String message) {
			super(message, null, true, false);
		}
	}
}
