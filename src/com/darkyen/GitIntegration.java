package com.darkyen;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
final class GitIntegration {

    private static final Logger LOG = Logger.getLogger("com.darkyen.GitIntegration");

    private static final String DTT_TIME_FILE_NAME = ".darkyenus_time_tracker_commit_time";

    static final long RESET_TIME_TO_ZERO = Long.MIN_VALUE;

    private final VirtualFile baseDir;

    GitIntegration(Project project) {
        this.baseDir = project.getBaseDir();
    }

    void updateVersionTimeFile(long versionSeconds) {
        final Application application = ApplicationManager.getApplication();
        application.invokeAndWait(() -> application.runWriteAction(() -> {
            final VirtualFile child = baseDir.findChild(".git");
            if (child == null) return;
            try {
                final VirtualFile timeFile = child.findOrCreateChildData(GitIntegration.class, DTT_TIME_FILE_NAME);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(timeFile.getInputStream(), StandardCharsets.UTF_8));
                final String line = reader.readLine();
                reader.close();
                long existingSeconds = 0;
                try {
                    if (line != null) {
                        existingSeconds = Long.parseLong(line);
                    }
                } catch (NumberFormatException ignored) {}

                final long newSeconds = versionSeconds == RESET_TIME_TO_ZERO ? 0 : Math.max(0, existingSeconds + versionSeconds);

                timeFile.setBinaryContent(Long.toString(newSeconds).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error while updating version time file", e);
            }
        }), ModalityState.defaultModalityState());
    }

    private static byte[] _prepare_commit_message_hook_content = null;
    private static byte[] prepareCommitMessageHookContent() throws IOException {
        if (_prepare_commit_message_hook_content == null) {
            _prepare_commit_message_hook_content = StreamUtil.loadFromStream(GitIntegration.class.getResourceAsStream("/hooks/"+PREPARE_COMMIT_MESSAGE_HOOK_NAME));
        }
        return _prepare_commit_message_hook_content;
    }

    private static final String PREPARE_COMMIT_MESSAGE_HOOK_NAME = "prepare-commit-msg";
    private static final String TIME_TRACKER_HOOK_IDENTIFIER = "#DarkyenusTimeTrackerHookScript";
    private static final String TIME_TRACKER_HOOK_IDENTIFIER_VERSIONED = TIME_TRACKER_HOOK_IDENTIFIER+"00003";

    private VirtualFile getHookDirectory() {
        final VirtualFile git = baseDir.findChild(".git");
        if (git == null || !git.isDirectory()) return null;
        final VirtualFile hooks = git.findChild("hooks");
        if (hooks == null || !hooks.isDirectory()) return null;
        return hooks;
    }

    private static void fillWithHookContent(VirtualFile hook) throws IOException {
        hook.setBinaryContent(prepareCommitMessageHookContent());
        final String canonicalPath = hook.getCanonicalPath();
        final File file = canonicalPath == null ? null : new File(canonicalPath);
        if (file == null || !file.isFile()) {
            LOG.log(Level.WARNING, "Hook file can't be marked as executable, can't create java.io.File from it.");
        } else {
            if(!file.canExecute() && !file.setExecutable(true)) {
                LOG.log(Level.WARNING, "Hook file can't be marked as executable, can't mark as executable");
            }
        }
    }

    void setupCommitHook(boolean enable) throws CommitHookException {
        ApplicationManager.getApplication().runWriteAction(() -> {
            final VirtualFile hookDirectory = getHookDirectory();
            if (hookDirectory == null) {
                if (enable) {
                    throw new CommitHookException("Git not initialized");
                }
                return;
            }
            try {
                final VirtualFile hook = hookDirectory.findChild(PREPARE_COMMIT_MESSAGE_HOOK_NAME);
                if (hook == null) {
                    if (enable) {
                        // Create new hook
                        final VirtualFile newHook = hookDirectory.createChildData(GitIntegration.class, PREPARE_COMMIT_MESSAGE_HOOK_NAME);
                        fillWithHookContent(newHook);
                    } else {
                        // All good, no hook is present
                        //noinspection UnnecessaryReturnStatement
                        return;
                    }
                } else {
                    //Is it our hook?
                    final String content = new String(hook.contentsToByteArray(), StandardCharsets.UTF_8);
                    final boolean isTimeTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER);
                    final boolean isRecentTrackerHook = content.contains(TIME_TRACKER_HOOK_IDENTIFIER_VERSIONED);
                    if (enable) {
                        if (isRecentTrackerHook) {
                            //All good, recent hook is present
                            //noinspection UnnecessaryReturnStatement
                            return;
                        } else if(isTimeTrackerHook) {
                            //There is our hook, but old, replace
                            fillWithHookContent(hook);
                        } else {
                            //This is not our hook!
                            throw new CommitHookException("There already is a "+PREPARE_COMMIT_MESSAGE_HOOK_NAME+" hook! Can't initialize.");
                        }
                    } else {
                        if (isTimeTrackerHook) {
                            //We are disabling and this is our hook, delete
                            hook.delete(GitIntegration.class);
                        } else {
                            //We are disabling and this is not our hook, leave it alone
                            //noinspection UnnecessaryReturnStatement
                            return;
                        }
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Failed to setupCommitHook", ex);
                throw new CommitHookException("Internal error, failed to "+(enable ? "enable" : "disable"));
            }
        });
    }

    static final class CommitHookException extends RuntimeException {
        CommitHookException(String message) {
            super(message, null, true, false);
        }
    }
}
