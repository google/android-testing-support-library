/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.test.internal.platform.util;

import androidx.test.internal.platform.ServiceLoaderWrapper;

/** A utility for outputting execution data files back to the test environment/CI solution. */
public class TestOutputEmitter {

  // create a handler for TestOutputEmitter calls. By default calls are no-ops
  private static final TestOutputHandler debugHandler =
      ServiceLoaderWrapper.loadSingleService(
          TestOutputHandler.class,
          () ->
              new TestOutputHandler() {
                @Override
                public void dumpThreadStates(String outputName) {}

                @Override
                public boolean takeScreenshot(String outputName) {
                  return false;
                }

                @Override
                public boolean captureWindowHierarchy(String outputName) {
                  return false;
                }
              });

  private TestOutputEmitter() {}

  /** Output the stack traces of all running threads back to the execution environment. */
  public static void dumpThreadStates(String outputName) {
    debugHandler.dumpThreadStates(outputName);
  }

  /** Output a screenshot back to the execution environment. */
  public static boolean takeScreenshot(String outputName) {
    return debugHandler.takeScreenshot(outputName);
  }

  /** Output the window hierarchy XML dump to the execution environment. */
  public static boolean captureWindowHierarchy(String outputName) {
    return debugHandler.captureWindowHierarchy(outputName);
  }
}
