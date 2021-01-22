/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.test.orchestrator;

/** Common constants for the AndroidTestOrchestrator and other classes. */
final class OrchestratorConstants {

  static final String TARGET_INSTRUMENTATION_ARGUMENT = "targetInstrumentation";
  static final String ISOLATED_ARGUMENT = "isolated";
  static final String ORCHESTRATOR_DEBUG_ARGUMENT = "orchestratorDebug";
  static final String COVERAGE_FILE_PATH = "coverageFilePath";
  static final String CLEAR_PKG_DATA = "clearPackageData";

  // The following args have equivalents in AJUR:
  static final String AJUR_LIST_TESTS_ARGUMENT = "listTestsForOrchestrator";
  static final String AJUR_CLASS_ARGUMENT = "class";
  static final String AJUR_COVERAGE = "coverage";
  static final String AJUR_COVERAGE_FILE = "coverageFile";

  private OrchestratorConstants() {
    // Do not initialize
  }
}
