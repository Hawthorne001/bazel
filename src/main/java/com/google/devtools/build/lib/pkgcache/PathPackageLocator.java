// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.pkgcache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PackageOptions.Code;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.OptionsProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A mapping from the name of a package to the location of its BUILD file. The implementation
 * composes an ordered sequence of directories according to the package-path rules.
 *
 * <p>All methods are thread-safe, and (assuming no change to the underlying filesystem) idempotent.
 */
public final class PathPackageLocator {
  private static final String WORKSPACE_WILDCARD = "%workspace%";

  private final ImmutableList<Root> pathEntries;

  // Transient because this is an injected value in Skyframe, and as such, its serialized
  // representation is used as a key. We want a change to output base not to invalidate things.
  @Nullable private final transient Path outputBase;

  private final ImmutableList<BuildFileName> buildFilesByPriority;

  @VisibleForTesting
  public PathPackageLocator(
      @Nullable Path outputBase, List<Root> pathEntries, List<BuildFileName> buildFilesByPriority) {
    this.outputBase = outputBase;
    this.pathEntries = ImmutableList.copyOf(pathEntries);
    this.buildFilesByPriority = ImmutableList.copyOf(buildFilesByPriority);
  }

  /**
   * Returns the path to the build file for this package, or null if not found.
   *
   * <p>The package's root directory may be computed by calling getParentFile() on the result of
   * this function.
   *
   * <p>If the same package exists beneath multiple package path entries, the first path that
   * matches always wins.
   *
   * @param packageIdentifier the name of the package.
   * @param syscallCache a filesystem-level cache of stat() calls.
   * @return the {@link Path} to the correct build file, or {@code null} if none was found
   */
  @Nullable
  public Path getPackageBuildFileNullable(
      PackageIdentifier packageIdentifier, SyscallCache syscallCache) {
    if (packageIdentifier.getRepository().isMain()) {
      for (BuildFileName buildFileName : buildFilesByPriority) {
        Path buildFilePath =
            getFilePath(
                packageIdentifier
                    .getPackageFragment()
                    .getRelative(buildFileName.getFilenameFragment()),
                syscallCache);
        if (buildFilePath != null) {
          return buildFilePath;
        }
      }
    } else {
      Verify.verify(outputBase != null, String.format(
          "External package '%s' needs to be loaded but this PathPackageLocator instance does not "
              + "support external packages", packageIdentifier));
      // This works only to some degree, because it relies on the presence of the repository under
      // $OUTPUT_BASE/external, which is created by the appropriate RepositoryDirectoryValue. This
      // is true for the invocation in GlobCache, but not for the locator.getBuildFileForPackage()
      // invocation in Parser#include().
      for (BuildFileName buildFileName : buildFilesByPriority) {
        Path buildFile =
            outputBase
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                .getRelative(packageIdentifier.getRepository().getName())
                .getRelative(packageIdentifier.getSourceRoot())
                .getRelative(buildFileName.getFilenameFragment());
        try {
          FileStatus stat = syscallCache.statIfFound(buildFile, Symlinks.FOLLOW);
          if (stat != null && stat.isFile()) {
            return buildFile;
          }
        } catch (IOException e) {
          return null;
        }
      }
    }

    return null;
  }

  /** Returns an immutable ordered list of the directories on the package path. */
  public ImmutableList<Root> getPathEntries() {
    return pathEntries;
  }

  @Override
  public String toString() {
    return "PathPackageLocator" + pathEntries;
  }

  public static String maybeReplaceWorkspaceInString(String pathElement, PathFragment workspace) {
    return pathElement.replace(WORKSPACE_WILDCARD, workspace.getPathString());
  }
  /**
   * A factory of PathPackageLocators from a list of path elements. Elements may contain
   * "%workspace%", indicating the workspace.
   *
   * <p>If any of the paths given do not exist, an exception will be thrown.
   *
   * @param outputBase the output base. Can be null if remote repositories are not in use.
   * @param pathElements Each element must be an absolute path, relative path, or some string
   *     "%workspace%" + relative, where relative is itself a relative path. The special symbol
   *     "%workspace%" means to interpret the path relative to the nearest enclosing workspace.
   *     Relative paths are interpreted relative to the client's working directory, which may be
   *     below the workspace.
   * @param eventHandler The eventHandler.
   * @param workspace The nearest enclosing package root directory.
   * @param clientWorkingDirectory The client's working directory.
   * @param buildFilesByPriority The ordered collection of {@link BuildFileName}s to check in each
   *     potential package directory.
   * @return a {@link PathPackageLocator} that uses the {@code outputBase} and {@code pathElements}
   *     provided.
   */
  public static PathPackageLocator create(
      Path outputBase,
      List<String> pathElements,
      EventHandler eventHandler,
      PathFragment workspace,
      Path clientWorkingDirectory,
      List<BuildFileName> buildFilesByPriority) {
    return createInternal(
        outputBase,
        pathElements,
        eventHandler,
        workspace,
        clientWorkingDirectory,
        buildFilesByPriority);
  }

  /**
   * A factory of PathPackageLocators from a list of path elements.
   *
   * @param outputBase the output base. Can be null if remote repositories are not in use.
   * @param pathElements Each element must be a {@link Root} object.
   * @param buildFilesByPriority The ordered collection of {@link BuildFileName}s to check in each
   *     potential package directory.
   * @return a {@link PathPackageLocator} that uses the {@code outputBase} and {@code pathElements}
   *     provided.
   */
  public static PathPackageLocator createWithoutExistenceCheck(
      @Nullable Path outputBase,
      List<Root> pathElements,
      List<BuildFileName> buildFilesByPriority) {
    return new PathPackageLocator(outputBase, pathElements, buildFilesByPriority);
  }

  private static PathPackageLocator createInternal(
      Path outputBase,
      List<String> pathElements,
      EventHandler eventHandler,
      PathFragment workspace,
      Path clientWorkingDirectory,
      List<BuildFileName> buildFilesByPriority) {
    List<Root> resolvedPaths = new ArrayList<>();

    for (String pathElement : pathElements) {
      // Replace "%workspace%" with the path of the enclosing workspace directory.
      pathElement = maybeReplaceWorkspaceInString(pathElement, workspace);

      PathFragment pathElementFragment = PathFragment.create(pathElement);

      // If the path string started with "%workspace%" or "/", it is already absolute, so the
      // following line returns a path pointing to pathElementFragment.
      Path rootPath = clientWorkingDirectory.getRelative(pathElementFragment);

      if (!pathElementFragment.isAbsolute()
          && !clientWorkingDirectory.asFragment().equals(workspace)) {
        eventHandler.handle(
            Event.warn(
                "The package path element '"
                    + pathElementFragment
                    + "' will be taken relative to your working directory. You may have intended to"
                    + " have the path taken relative to your workspace directory. If so, please use"
                    + "the '"
                    + WORKSPACE_WILDCARD
                    + "' wildcard."));
      }

      if (rootPath.exists()) {
        resolvedPaths.add(Root.fromPath(rootPath));
      }
    }

    return new PathPackageLocator(outputBase, resolvedPaths, buildFilesByPriority);
  }

  /**
   * Extracts the package path from the {@code --package_path} flag, which is expected to have a
   * single entry.
   *
   * <p>May be used to get the real package path when a {@linkplain
   * BlazeDirectories#getVirtualSourceRoot virtual source root} is installed.
   */
  public static String getSingletonPackagePathFromFlag(
      OptionsProvider options, BlazeDirectories directories) throws AbruptExitException {
    List<String> packagePaths = options.getOptions(PackageOptions.class).packagePath;
    if (packagePaths.size() != 1) {
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(
                      String.format(
                          "Package path option must have exactly 1 value: %s", packagePaths))
                  .setPackageOptions(
                      FailureDetails.PackageOptions.newBuilder()
                          .setCode(Code.NONSINGLETON_PACKAGE_PATH))
                  .build()));
    }
    return maybeReplaceWorkspaceInString(
        packagePaths.getFirst(), directories.getWorkspace().asFragment());
  }

  /**
   * Returns the path to the WORKSPACE file for this build.
   *
   * <p>If there are WORKSPACE files beneath multiple package path entries, the first one always
   * wins.
   */
  public Path getWorkspaceFile(SyscallCache syscallCache) {
    // TODO(bazel-team): correctness in the presence of changes to the location of the WORKSPACE
    //  file.
    Path workspaceFile = getFilePath(LabelConstants.WORKSPACE_DOT_BAZEL_FILE_NAME, syscallCache);
    if (workspaceFile != null) {
      return workspaceFile;
    }
    return getFilePath(LabelConstants.WORKSPACE_FILE_NAME, syscallCache);
  }

  @Nullable
  private Path getFilePath(PathFragment suffix, SyscallCache cache) {
    for (Root pathEntry : pathEntries) {
      Path buildFile = pathEntry.getRelative(suffix);
      try {
        SyscallCache.DirentTypeWithSkip typeWithSkip = cache.getType(buildFile, Symlinks.FOLLOW);
        Dirent.Type type = null;
        if (typeWithSkip == SyscallCache.DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED) {
          type = SyscallCache.statusToDirentType(cache.statIfFound(buildFile, Symlinks.FOLLOW));
        } else if (typeWithSkip != null) {
          type = typeWithSkip.getType();
        }
        if (type == Dirent.Type.FILE || type == Dirent.Type.UNKNOWN) {
          return buildFile;
        }
      } catch (IOException ignored) {
        // Treat IOException as a missing file.
      }
    }
    return null;
  }

  @Override
  public int hashCode() {
    return Objects.hash(pathEntries, outputBase);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PathPackageLocator pathPackageLocator)) {
      return false;
    }
    return Objects.equals(pathEntries, pathPackageLocator.pathEntries)
        && Objects.equals(outputBase, pathPackageLocator.outputBase);
  }

  @Nullable
  public Path getOutputBase() {
    return outputBase;
  }
}
