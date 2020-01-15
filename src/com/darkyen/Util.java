package com.darkyen;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility methods.
 */
public final class Util {

	/** Rounded conversion of milliseconds to seconds. */
	public static long msToS(long ms) {
		return (ms + 500L) / 1000L;
	}

	@Nullable
	public static VirtualFile getProjectBaseDir(@Nullable Project project) {
		if (project == null) {
			return null;
		}
		final String basePath = PathUtil.toSystemIndependentName(project.getBasePath());
		if (basePath == null) {
			return null;
		}
		return LocalFileSystem.getInstance().findFileByPath(basePath);
	}

	@Nullable
	public static Path convertToIOFile(@Nullable VirtualFile file) {
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
