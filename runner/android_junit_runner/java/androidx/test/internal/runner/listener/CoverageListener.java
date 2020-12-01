/*
 * Copyright (C) 2012 The Android Open Source Project
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
package androidx.test.internal.runner.listener;

import android.app.Instrumentation;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.test.internal.runner.coverage.InstrumentationCoverageReporter;
import androidx.test.services.storage.internal.InternalTestStorage;
import java.io.PrintStream;
import org.junit.runner.Result;

/**
 * A test <a href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html">
 * <code>RunListener</code></a> that generates EMMA code coverage.
 */
public class CoverageListener extends InstrumentationRunListener {
  private final String coverageFilePath;
  private boolean isTestServiceAvailable;
  private InstrumentationCoverageReporter coverageReporter;

  /**
   * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
   * identifies the path to the generated code coverage file.
   */
  private static final String REPORT_KEY_COVERAGE_PATH = "coverageFilePath";

  /**
   * Constructor.
   *
   * <p>By default, we assume that the test storage is not available.
   *
   * @param customCoverageFilePath an optional user specified path for the coverage file. Can be
   *     {@code null}.
   */
  public CoverageListener(@Nullable String customCoverageFilePath) {
    this(customCoverageFilePath, false /* isTestServiceAvailable */);
  }

  /**
   * Constructor.
   *
   * @param customCoverageFilePath an optional user specified path for the coverage file. Can be
   *     {@code null}.
   * @param isTestServiceAvailable specifies whether the test storage service is present when
   *     running the Instrumentation tests.
   */
  public CoverageListener(@Nullable String customCoverageFilePath, boolean isTestServiceAvailable) {
    coverageFilePath = customCoverageFilePath;
    this.isTestServiceAvailable = isTestServiceAvailable;
  }

  @VisibleForTesting
  CoverageListener(
      @Nullable String customCoverageFilePath, InstrumentationCoverageReporter coverageReporter) {
    this.coverageFilePath = customCoverageFilePath;
    this.coverageReporter = coverageReporter;
  }

  /**
   * Passes in the instrumentation instance, and initializes the test storage service if it's
   * available on the device.
   *
   * @param instr the instrumentation instance. Must not be {@code null}.
   */
  @Override
  public void setInstrumentation(Instrumentation instr) {
    super.setInstrumentation(instr);
    // Initializes the coverage reporter.
    InternalTestStorage testStorage = isTestServiceAvailable ? new InternalTestStorage() : null;
    coverageReporter = new InstrumentationCoverageReporter(instrumentation, testStorage);
  }

  @Override
  public void instrumentationRunFinished(PrintStream writer, Bundle results, Result junitResults) {
    String actualCoveragePath = coverageReporter.generateCoverageReport(coverageFilePath, writer);
    results.putString(REPORT_KEY_COVERAGE_PATH, actualCoveragePath);
  }
}
