// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.NoErrorCollectPackagesUnderDirectoryValue;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for codec for {@link CollectPackagesUnderDirectoryValue}. */
@RunWith(JUnit4.class)
public final class CollectPackagesUnderDirectoryCodecTest {

  @Test
  public void testCodec() throws Exception {
    SerializationTester serializationTester =
        new SerializationTester(
            NoErrorCollectPackagesUnderDirectoryValue.EMPTY,
            CollectPackagesUnderDirectoryValue.ofNoError(
                true,
                ImmutableMap.of(
                    rootedPath("/a", "b"), true,
                    rootedPath("/c", "d"), false)),
            CollectPackagesUnderDirectoryValue.ofNoError(
                false,
                ImmutableMap.of(
                    rootedPath("/a", "b"), false,
                    rootedPath("/c", "d"), true)),
            CollectPackagesUnderDirectoryValue.ofError(
                "my error message",
                ImmutableMap.of(
                    rootedPath("/a", "b"), false,
                    rootedPath("/c", "d"), true)));
    FsUtils.addDependencies(serializationTester);
    serializationTester.runTests();
  }

  @Test
  public void testEmptyDeserializesToSingletonValue() throws Exception {
    assertThat(RoundTripping.roundTrip(NoErrorCollectPackagesUnderDirectoryValue.EMPTY))
        .isSameInstanceAs(NoErrorCollectPackagesUnderDirectoryValue.EMPTY);
  }

  private static RootedPath rootedPath(String root, String relativePath) {
    return RootedPath.toRootedPath(
        Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath(root)), PathFragment.create(relativePath));
  }
}
