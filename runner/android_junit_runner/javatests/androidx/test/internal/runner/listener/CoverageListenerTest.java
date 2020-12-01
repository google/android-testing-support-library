/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.internal.runner.coverage.InstrumentationCoverageReporter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test cases for {@link CoverageListener}. */
@RunWith(AndroidJUnit4.class)
public class CoverageListenerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private InstrumentationCoverageReporter coverageReporter;
  private PrintStream instrumentationResultWriter;

  @Before
  public void setUp() {
    instrumentationResultWriter = new PrintStream(new ByteArrayOutputStream());

    when(coverageReporter.generateCoverageReport(
            eq("/path/to/coverage_file"), eq(instrumentationResultWriter)))
        .thenReturn("/path/to/coverage_file");
    when(coverageReporter.generateCoverageReport(
            null /* coverageFilePath */, instrumentationResultWriter))
        .thenReturn("/default_path/to/coverage_file");
  }

  @Test
  public void instrumentationRunFinished() {
    CoverageListener coverageListener =
        new CoverageListener("/path/to/coverage_file", coverageReporter);
    Bundle resultBundle = new Bundle();

    coverageListener.instrumentationRunFinished(instrumentationResultWriter, resultBundle, null);

    assertThat(resultBundle.getString("coverageFilePath")).isEqualTo("/path/to/coverage_file");
  }

  @Test
  public void instrumentationRunFinished_defaultPath() {
    CoverageListener coverageListener =
        new CoverageListener(null /* customCoverageFilePath */, coverageReporter);
    Bundle resultBundle = new Bundle();

    coverageListener.instrumentationRunFinished(instrumentationResultWriter, resultBundle, null);

    assertThat(resultBundle.getString("coverageFilePath"))
        .isEqualTo("/default_path/to/coverage_file");
  }

  @Test
  public void instrumentationRunFinished_failedTodumpCoverage() {
    CoverageListener coverageListener =
        new CoverageListener("/path/to/coverage_file", coverageReporter);
    when(coverageReporter.generateCoverageReport(
            eq("/path/to/coverage_file"), eq(instrumentationResultWriter)))
        .thenReturn(null);
    Bundle resultBundle = new Bundle();

    coverageListener.instrumentationRunFinished(instrumentationResultWriter, resultBundle, null);

    assertThat(resultBundle.getString("coverageFilePath")).isNull();
  }
}
