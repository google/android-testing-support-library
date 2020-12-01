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
package androidx.test.internal.runner.coverage;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.services.storage.internal.InternalTestStorage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test cases for {@link InstrumentationCoverageReporter}. */
@RunWith(AndroidJUnit4.class)
public class InstrumentationCoverageReporterTest {
  private Instrumentation instrumentation;
  private InternalTestStorage testStorage;
  private PrintStream instrumentationResultWriter;
  private InstrumentationCoverageReporter spyCoverageReporter;
  private String defaultAppDataFolder;
  private String defaultCoverageFilePath;

  @Before
  public void setUp() throws IOException {
    instrumentation = getInstrumentation();
    instrumentationResultWriter = new PrintStream(new ByteArrayOutputStream());
    testStorage = new InternalTestStorage();

    spyCoverageReporter = spy(new InstrumentationCoverageReporter(instrumentation, testStorage));
    defaultAppDataFolder =
        instrumentation.getTargetContext().getFilesDir().getAbsolutePath() + File.separator;
    defaultCoverageFilePath = defaultAppDataFolder + "coverage.ec";

    doReturn(true)
        .when(spyCoverageReporter)
        .generateCoverageInternal(anyString(), eq(instrumentationResultWriter));
  }

  @Test
  public void generateCoverageReport() throws IOException {
    try (FileOutputStream out = new FileOutputStream(new File(defaultCoverageFilePath))) {
      out.write("Coverage data dump".getBytes());
    }

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(
            "path/to/coverage.ec", instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo("path/to/coverage.ec");
    try (InputStream in = testStorage.openInternalInputStream("path/to/coverage.ec")) {
      byte[] coverageData = new byte["Coverage data dump".length()];
      in.read(coverageData);
      assertThat(coverageData).isEqualTo("Coverage data dump".getBytes());
    }
    verify(spyCoverageReporter)
        .generateCoverageInternal(defaultCoverageFilePath, instrumentationResultWriter);
  }

  @Test
  public void generateCoverageReport_defaultCoveragePath() throws IOException {
    try (FileOutputStream out = new FileOutputStream(new File(defaultCoverageFilePath))) {
      out.write("Coverage data dump".getBytes());
    }

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(
            null /* coverageFilePath */, instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo("coverage.ec");
    try (InputStream in = testStorage.openInternalInputStream("coverage.ec")) {
      byte[] coverageData = new byte["Coverage data dump".length()];
      in.read(coverageData);
      assertThat(coverageData).isEqualTo("Coverage data dump".getBytes());
    }
    verify(spyCoverageReporter)
        .generateCoverageInternal(defaultCoverageFilePath, instrumentationResultWriter);
  }

  @Test
  public void generateCoverageReport_failedToGenerateReport() throws IOException {
    // An empty file will be generated when JaCoCo failed to generate the coverage execution data
    // report.
    new File(defaultCoverageFilePath).createNewFile();
    doReturn(false)
        .when(spyCoverageReporter)
        .generateCoverageInternal(eq(defaultCoverageFilePath), eq(instrumentationResultWriter));

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(
            "path/to/coverage2.ec", instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo("path/to/coverage2.ec");
    // The coverage file should be empty.
    try (InputStream in = testStorage.openInternalInputStream(actualCoverageFilePath)) {
      assertThat(in.read()).isEqualTo(-1); // Eof
    }
  }

  @Test
  public void generateCoverageReport_noTestService() throws IOException {
    // Stubs the coverage reporter.
    spyCoverageReporter =
        spy(new InstrumentationCoverageReporter(instrumentation, null /* testStorage */));
    String coverageFilePath = defaultAppDataFolder + "this_is_coverage.ec";
    doReturn(true)
        .when(spyCoverageReporter)
        .generateCoverageInternal(eq(coverageFilePath), eq(instrumentationResultWriter));

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(coverageFilePath, instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo(coverageFilePath);
  }

  @Test
  public void generateCoverageReport_noTestService_sameAsDefaultPath() throws IOException {
    // Stubs the coverage reporter.
    spyCoverageReporter =
        spy(new InstrumentationCoverageReporter(instrumentation, null /* testStorage */));
    doReturn(true)
        .when(spyCoverageReporter)
        .generateCoverageInternal(eq(defaultCoverageFilePath), eq(instrumentationResultWriter));

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(
            defaultCoverageFilePath, instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo(defaultCoverageFilePath);
  }

  @Test
  public void generateCoverageReport_noTestService_defaultCoveragePath() throws IOException {
    // Stubs the coverage reporter.
    spyCoverageReporter =
        spy(new InstrumentationCoverageReporter(instrumentation, null /* testStorage */));
    doReturn(true)
        .when(spyCoverageReporter)
        .generateCoverageInternal(eq(defaultCoverageFilePath), eq(instrumentationResultWriter));

    String actualCoverageFilePath =
        spyCoverageReporter.generateCoverageReport(
            null /* coverageFilePath */, instrumentationResultWriter);

    assertThat(actualCoverageFilePath).isEqualTo(defaultCoverageFilePath);
  }
}
