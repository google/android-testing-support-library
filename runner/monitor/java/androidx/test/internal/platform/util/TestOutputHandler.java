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

/**
 * An extension definition for outputting debugging information to the test execution environment.
 *
 * <p>Implementations should be provided via {@link ServiceLoader}
 */
public interface TestOutputHandler {

  /** Output the stack traces of all running threads back to the execution environment. */
  void dumpThreadStates(String outputName);

  /** Take a screenshot and store it in test outputs */
  boolean takeScreenshot(String outputName);

  /** Dump the window hierarchy and store it in test outputs */
  boolean captureWindowHierarchy(String outputName);
}
