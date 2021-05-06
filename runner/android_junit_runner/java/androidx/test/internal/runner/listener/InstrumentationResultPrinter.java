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
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import androidx.test.services.events.internal.StackTrimmer;
import java.io.PrintStream;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * A <a href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html"><code>
 * RunListener</code></a> that sends detailed pass/fail results back as instrumentation status
 * bundles.
 *
 * <p>When running instrumentation in '-r' or raw mode, output will be displayed in this format
 *
 * <pre>
 *     INSTRUMENTATION_STATUS: key=value
 * </pre>
 *
 * for each key-value pair in the bundle.
 *
 * <p>When running in normal aka non raw mode, only the value of the
 * Instrumentation.REPORT_KEY_STREAMRESULT key will be displayed.
 */
public class InstrumentationResultPrinter extends InstrumentationRunListener {

  private static final String TAG = "InstrumentationResultPrinter";

  /**
   * This value, if stored with key {@link android.app.Instrumentation#REPORT_KEY_IDENTIFIER},
   * identifies AndroidJUnitRunner as the source of the report. This is sent with all status
   * messages.
   */
  public static final String REPORT_VALUE_ID = "AndroidJUnitRunner";
  /**
   * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
   * identifies the total number of tests that are being run. This is sent with all status messages.
   */
  public static final String REPORT_KEY_NUM_TOTAL = "numtests";
  /**
   * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
   * identifies the sequence number of the current test. This is sent with any status message
   * describing a specific test being started or completed.
   */
  public static final String REPORT_KEY_NUM_CURRENT = "current";
  /**
   * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
   * identifies the name of the current test class. This is sent with any status message describing
   * a specific test being started or completed.
   */
  public static final String REPORT_KEY_NAME_CLASS = "class";
  /**
   * If included in the status or final bundle sent to an IInstrumentationWatcher, this key
   * identifies the name of the current test. This is sent with any status message describing a
   * specific test being started or completed.
   */
  public static final String REPORT_KEY_NAME_TEST = "test";

  /** The test is starting. */
  public static final int REPORT_VALUE_RESULT_START = 1;
  /** The test completed successfully. */
  public static final int REPORT_VALUE_RESULT_OK = 0;
  /**
   * The test completed with an error.
   *
   * @deprecated not supported in JUnit4, use REPORT_VALUE_RESULT_FAILURE instead
   */
  @Deprecated public static final int REPORT_VALUE_RESULT_ERROR = -1;
  /** The test completed with a failure. */
  public static final int REPORT_VALUE_RESULT_FAILURE = -2;
  /** The test was ignored. */
  public static final int REPORT_VALUE_RESULT_IGNORED = -3;
  /** The test completed with an assumption failure. */
  public static final int REPORT_VALUE_RESULT_ASSUMPTION_FAILURE = -4;

  /**
   * If included in the status bundle sent to an IInstrumentationWatcher, this key identifies a
   * stack trace describing an error or failure. This is sent with any status message describing a
   * specific test being completed.
   */
  public static final String REPORT_KEY_STACK = "stack";

  private final Bundle resultTemplate;
  @VisibleForTesting Bundle testResult;
  int testNum = 0;
  int testResultCode = -999;
  String testClass = null;
  private Description description = Description.EMPTY;

  public InstrumentationResultPrinter() {
    resultTemplate = new Bundle();
    testResult = new Bundle(resultTemplate);
  }

  @Override
  public void testRunStarted(Description description) throws Exception {
    resultTemplate.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
    resultTemplate.putInt(REPORT_KEY_NUM_TOTAL, description.testCount());
  }

  /** send a status for the start of a each test, so long tests can be seen as "running" */
  @Override
  public void testStarted(Description description) throws Exception {
    this.description = description; // cache Description in case of a crash
    String testClass = description.getClassName();
    String testName = description.getMethodName();
    testResult = new Bundle(resultTemplate);
    testResult.putString(REPORT_KEY_NAME_CLASS, testClass);
    testResult.putString(REPORT_KEY_NAME_TEST, testName);
    testResult.putInt(REPORT_KEY_NUM_CURRENT, ++testNum);
    // pretty printing
    if (testClass != null && !testClass.equals(this.testClass)) {
      testResult.putString(
          Instrumentation.REPORT_KEY_STREAMRESULT, String.format("\n%s:", testClass));
      this.testClass = testClass;
    } else {
      testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "");
    }

    sendStatus(REPORT_VALUE_RESULT_START, testResult);
    testResultCode = REPORT_VALUE_RESULT_OK;
  }

  @Override
  public void testFinished(Description description) throws Exception {
    if (testResultCode == REPORT_VALUE_RESULT_OK) {
      testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, ".");
    }
    sendStatus(testResultCode, testResult);
  }

  @Override
  public void testFailure(Failure failure) throws Exception {
    boolean shouldCallFinish = false;
    if (description.equals(Description.EMPTY) && testNum == 0 && testClass == null) {
      // Junit failed during initialization and testStarted was never called. For example, an
      // exception was thrown in @BeforeClass method. We must artificially call testStarted and
      // testFinished in order to print a descriptive result that external tools (like Studio) can
      // understand.
      testStarted(failure.getDescription());
      shouldCallFinish = true;
    }
    testResultCode = REPORT_VALUE_RESULT_FAILURE;
    reportFailure(failure);
    if (shouldCallFinish) {
      testFinished(failure.getDescription());
    }
  }

  @Override
  public void testAssumptionFailure(Failure failure) {
    testResultCode = REPORT_VALUE_RESULT_ASSUMPTION_FAILURE;
    testResult.putString(REPORT_KEY_STACK, failure.getTrace());
  }

  private void reportFailure(Failure failure) {
    String trace = StackTrimmer.getTrimmedStackTrace(failure);
    testResult.putString(REPORT_KEY_STACK, trace);
    // pretty printing
    testResult.putString(
        Instrumentation.REPORT_KEY_STREAMRESULT,
        String.format("\nError in %s:\n%s", failure.getDescription().getDisplayName(), trace));
  }

  @Override
  public void testIgnored(Description description) throws Exception {
    testStarted(description);
    testResultCode = REPORT_VALUE_RESULT_IGNORED;
    testFinished(description);
  }

  /**
   * Produce a more meaningful crash report including stack trace and report it back to
   * Instrumentation results.
   */
  public void reportProcessCrash(Throwable t) {
    try {
      testResultCode = REPORT_VALUE_RESULT_FAILURE;
      Failure failure = new Failure(description, t);
      testResult.putString(REPORT_KEY_STACK, failure.getTrace());
      // pretty printing
      testResult.putString(
          Instrumentation.REPORT_KEY_STREAMRESULT,
          String.format(
              "\nProcess crashed while executing %s:\n%s",
              description.getDisplayName(), failure.getTrace()));
      testFinished(description);
    } catch (Exception e) {
      // ignore, about to crash anyway
      if (null == description) {
        Log.e(TAG, "Failed to initialize test before process crash");
      } else {
        Log.e(
            TAG,
            "Failed to mark test "
                + description.getDisplayName()
                + " as finished after process crash");
      }
    }
  }

  @Override
  public void instrumentationRunFinished(
      PrintStream streamResult, Bundle resultBundle, Result junitResults) {
    // reuse JUnit TextListener to display a summary of the run
    new TestSummaryListener(streamResult).testRunFinished(junitResults);
  }
}
