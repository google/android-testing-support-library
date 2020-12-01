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

import android.app.Instrumentation;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import androidx.test.services.storage.internal.InternalTestStorage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/** A class that generates the JaCoCo execution data in Android Instrumentation tests. */
public class InstrumentationCoverageReporter {
  private static final String TAG = InstrumentationCoverageReporter.class.getSimpleName();

  private static final String EMMA_RUNTIME_CLASS = "com.vladium.emma.rt.RT";
  private static final String DEFAULT_COVERAGE_FILE_NAME = "coverage.ec";

  private final Instrumentation instrumentation;
  @Nullable private final InternalTestStorage testStorage;

  /**
   * Constructor.
   *
   * @param instrumentation the instrumentation instance. Must not be {@code null}.
   * @param testStorage the test storage instance for I/O operations in an instrumentation test.
   *     {@code null} if the test storage service is not present.
   */
  public InstrumentationCoverageReporter(
      Instrumentation instrumentation, @Nullable InternalTestStorage testStorage) {
    this.instrumentation = instrumentation;
    this.testStorage = testStorage;
  }

  /**
   * Generates the JaCoCo execution data report in the specified file path. A default file path will
   * be used if no file path was provided, depending on whether the test storage service is
   * available:
   *
   * <ul>
   *   <li>If the test storage service is available, the coverage file will be generated as
   *       coverage.ec under the test storage managed internal directory.
   *   <li>Otherwise, the coverage file will be generated under the test app's file folder, i.e.
   *       /data/data/<app-package>/files/coverage.ec.
   * </ul>
   *
   * <p>Note, when the test storage service is not available, the caller of this method is
   * responsible to make sure the given file path is writable.
   *
   * @param coverageFilePath the file path to generate the coverage data report. This is a relative
   *     path when the test storage service is available, otherwise an absolute path on the device.
   *     Can be {@code null}.
   * @param instrumentationResultWriter the writer that can be used to write to the Instrumentation
   *     summary result.
   * @return the actual file path the coverage report was written to, when the provided path is
   *     {@code null}.
   */
  public String generateCoverageReport(
      @Nullable String coverageFilePath, PrintStream instrumentationResultWriter) {
    if (testStorage == null) {
      coverageFilePath = dumpCoverageToFile(coverageFilePath, instrumentationResultWriter);
    } else {
      coverageFilePath = dumpCoverageToTestStorage(coverageFilePath, instrumentationResultWriter);
    }
    Log.d(TAG, "Coverage file was generated to " + coverageFilePath);
    // Also outputs a more user friendly message.
    instrumentationResultWriter.format("\nGenerated code coverage data to %s", coverageFilePath);
    return coverageFilePath;
  }

  /**
   * Directly write the coverage execution data to file when the test storage service is not
   * installed on the device.
   */
  private String dumpCoverageToFile(
      String coverageFilePath, PrintStream instrumentationResultWriter) {
    if (coverageFilePath == null) {
      Log.d(TAG, "No coverage file path was specified. Dumps to the default file path.");
      coverageFilePath =
          instrumentation.getTargetContext().getFilesDir().getAbsolutePath()
              + File.separator
              + DEFAULT_COVERAGE_FILE_NAME;
    }

    if (!generateCoverageInternal(coverageFilePath, instrumentationResultWriter)) {
      Log.w(
          TAG,
          "Failed to generate the coverage data file. Please refer to the instrumentation result"
              + " for more info.");
    }
    return coverageFilePath;
  }

  /**
   * Dumps the coverage execution data to file and then moves it to the test storage internal
   * folder.
   */
  private String dumpCoverageToTestStorage(
      String coverageFilePath, PrintStream instrumentationResultWriter) {
    if (coverageFilePath == null) {
      Log.d(
          TAG,
          "No coverage file path was specified. Dumps to the default coverage file using test"
              + " storage.");
      coverageFilePath = DEFAULT_COVERAGE_FILE_NAME;
    }

    String tempCoverageFilePath =
        instrumentation.getTargetContext().getFilesDir().getAbsolutePath()
            + File.separator
            + DEFAULT_COVERAGE_FILE_NAME;
    if (!generateCoverageInternal(tempCoverageFilePath, instrumentationResultWriter)) {
      Log.w(
          TAG,
          "Failed to generate the coverage data file. Please refer to the instrumentation result"
              + " for more info.");
    }

    try {
      Log.d(
          TAG,
          "Test service is available. Moving the coverage data file to be managed by the storage"
              + " service.");
      moveFileToTestStorage(tempCoverageFilePath, coverageFilePath);
      return coverageFilePath;
    } catch (IOException e) {
      reportEmmaError(instrumentationResultWriter, e);
    }
    return null;
  }

  private void moveFileToTestStorage(String srcFilePath, String destFilePath) throws IOException {
    File srcFile = new File(srcFilePath);
    if (srcFile.exists()) {
      Log.d(
          TAG,
          String.format(
              "Moving coverage file [%s] to the internal test storage [%s].",
              srcFilePath, destFilePath));
      try (OutputStream outputStream = testStorage.openInternalOutputStream(destFilePath);
          FileChannel srcChannel = new FileInputStream(srcFilePath).getChannel();
          WritableByteChannel destChannel = Channels.newChannel(outputStream)) {
        srcChannel.transferTo(0 /* position */, srcChannel.size() /* count */, destChannel);
      }
      if (!srcFile.delete()) {
        Log.e(
            TAG,
            String.format(
                "Failed to delete original coverage file [%s]", srcFile.getAbsolutePath()));
      }
    }
  }

  /**
   * Uses the JaCoCo agent to dump the execution data file to the given file path.
   *
   * @return true if the coverage data was successfully generated, false otherwise.
   */
  // Has to be `public` so that Mockito could properly stub it in testing.
  @VisibleForTesting
  public boolean generateCoverageInternal(
      String coverageFilePath, PrintStream instrumentationResultWriter) {
    java.io.File coverageFile = new java.io.File(coverageFilePath);

    try {
      // In case the target and instrumentation contexts are different, prioritize coverage from the
      // target context. If the target context classloader implements a delegate-first strategy
      // for org.jacoco.agent.rt and com.vladium.emma.rt, it will still be possible to get coverage
      // from either, or both, contexts.
      Class<?> emmaRTClass;
      try {
        emmaRTClass =
            Class.forName(
                EMMA_RUNTIME_CLASS, true, instrumentation.getTargetContext().getClassLoader());
      } catch (ClassNotFoundException e) {
        emmaRTClass =
            Class.forName(EMMA_RUNTIME_CLASS, true, instrumentation.getContext().getClassLoader());
        String msg = "Generating coverage for alternate test context.";
        Log.w(TAG, msg);
        instrumentationResultWriter.format("\nWarning: %s", msg);
      }

      // Uses reflection to call emma dump coverage method, to avoid always statically compiling
      // against the JaCoCo jar. The test infrastructure should make sure the JaCoCo library is
      // available when collecting the coverage data.
      Method dumpCoverageMethod =
          emmaRTClass.getMethod(
              "dumpCoverageData", coverageFile.getClass(), boolean.class, boolean.class);
      dumpCoverageMethod.invoke(null, coverageFile, false, false);
      return true;
    } catch (ClassNotFoundException e) {
      reportEmmaError(instrumentationResultWriter, "Is Emma/JaCoCo jar on classpath?", e);
    } catch (SecurityException
        | NoSuchMethodException
        | IllegalArgumentException
        | IllegalAccessException
        | InvocationTargetException e) {
      reportEmmaError(instrumentationResultWriter, e);
    }
    return false;
  }

  private void reportEmmaError(PrintStream writer, Exception e) {
    reportEmmaError(writer, "", e);
  }

  private void reportEmmaError(PrintStream writer, String hint, Exception e) {
    String msg = "Failed to generate Emma/JaCoCo coverage. " + hint;
    Log.e(TAG, msg, e);
    writer.format("\nError: %s", msg);
  }
}
