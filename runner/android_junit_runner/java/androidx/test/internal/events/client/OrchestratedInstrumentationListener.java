/*
 * Copyright (C) 2016 The Android Open Source Project
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

package androidx.test.internal.events.client;

import static androidx.test.internal.util.Checks.checkNotNull;
import static androidx.test.services.events.ParcelableConverter.getFailure;
import static androidx.test.services.events.ParcelableConverter.getFailuresFromList;
import static androidx.test.services.events.ParcelableConverter.getTestCaseFromDescription;
import static java.util.Collections.emptyList;

import android.os.ConditionVariable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import androidx.test.services.events.FailureInfo;
import androidx.test.services.events.TestCaseInfo;
import androidx.test.services.events.TestEventException;
import androidx.test.services.events.run.TestAssumptionFailureEvent;
import androidx.test.services.events.run.TestFailureEvent;
import androidx.test.services.events.run.TestFinishedEvent;
import androidx.test.services.events.run.TestIgnoredEvent;
import androidx.test.services.events.run.TestRunFinishedEvent;
import androidx.test.services.events.run.TestRunStartedEvent;
import androidx.test.services.events.run.TestStartedEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * A {@link RunListener} for the orchestrated instrumentation to communicate test run notifications
 * back to the remote {@link androidx.test.orchestrator.OrchestratorService} or {@link
 * androidx.test.services.events.run.ITestRunEvent} service.
 */
public final class OrchestratedInstrumentationListener extends RunListener {
  // TODO(b/161754141): replace references to the word "orchestrator" with "test event service"
  private static final String TAG = "OrchestrationListener";
  private final TestRunEventService notificationService;
  private final ConditionVariable testFinishedCondition = new ConditionVariable();
  private final AtomicBoolean isTestFailed = new AtomicBoolean(false);
  private final AtomicBoolean isTestFinished = new AtomicBoolean(false);
  private final AtomicBoolean isTestRunFinished = new AtomicBoolean(false);
  private Description description = Description.EMPTY; // Cached test description

  /**
   * Creates the {@link OrchestratedInstrumentationListener} to communicate with the remote test run
   * events service.
   *
   * @param notificationService the remote service to send test run events to
   */
  public OrchestratedInstrumentationListener(@NonNull TestRunEventService notificationService) {
    super();
    checkNotNull(notificationService, "notificationService cannot be null");
    this.notificationService = notificationService;
  }

  /** {@inheritDoc} */
  @Override
  public void testRunStarted(Description description) {
    // New run, reset our values
    isTestFailed.set(false);
    isTestFinished.set(false);
    isTestRunFinished.set(false);
    try {
      notificationService.send(new TestRunStartedEvent(getTestCaseFromDescription(description)));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestRunStartedEvent to Orchestrator", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void testRunFinished(Result result) {
    if (!isTestRunFinished.compareAndSet(false, true)) {
      return; // we already reported this test run, don't report it again.
    }

    List<FailureInfo> failures = emptyList();
    try {
      failures = getFailuresFromList(result.getFailures());
    } catch (TestEventException e) {
      Log.w(TAG, "Failure event doesn't contain a test case", e);
    }
    try {
      notificationService.send(
          new TestRunFinishedEvent(
              result.getRunCount(), result.getIgnoreCount(), result.getRunTime(), failures));

    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestRunFinishedEvent to Orchestrator", e);
    }
  }

  /**
   * This is a clone of testRunFinished. Unfortunately the API for Result is locked down so we have
   * to create our own filler crash data here.
   */
  private void testRunCrashed(Failure failure) {
    if (!isTestRunFinished.compareAndSet(false, true)) {
      return; // we already reported this test run, don't report it again.
    }

    List<FailureInfo> failures = emptyList();
    try {
      failures.add(getFailure(failure));
    } catch (TestEventException e) {
      Log.w(TAG, "Failure event doesn't contain a test case", e);
    }
    try {
      // We don't have any information about this test suite, send some obviously bad data.
      notificationService.send(new TestRunFinishedEvent(-1, -1, 0, failures));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestRunFinishedEvent to Orchestrator", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void testStarted(Description description) {
    isTestFailed.set(false); // new test, it's not failed yet.
    isTestFinished.set(false); // new test, it's not finished yet.
    this.description = description; // Caches the test description in case of a crash
    if (!JUnitValidator.validateDescription(description)) {
      Log.w(
          TAG,
          "testStarted: JUnit reported "
              + description.getClassName()
              + "#"
              + description.getMethodName()
              + "; discarding as bogus.");
      return;
    }
    try {
      notificationService.send(new TestStartedEvent(getTestCaseFromDescription(description)));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestStartedEvent to Orchestrator", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void testFinished(Description description) {
    if (!isTestFinished.compareAndSet(false, true)) {
      return; // we already reported this test, don't report it again.
    }

    if (!JUnitValidator.validateDescription(description)) {
      Log.w(
          TAG,
          "testFinished: JUnit reported "
              + description.getClassName()
              + "#"
              + description.getMethodName()
              + "; discarding as bogus.");
      return;
    }
    try {
      notificationService.send(new TestFinishedEvent(getTestCaseFromDescription(description)));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestFinishedEvent to Orchestrator", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void testFailure(Failure failure) {
    // This block can be called by the JUnit test framework when a failure happened in the test,
    // or {@link #reportProcessCrash(Throwable)} when we'd like to report a process crash as a
    // failure.
    // We'd like to make sure only one failure gets sent so that the isTestFailed variable is
    // checked and set without possibly racing between two thread calls.
    if (!isTestFailed.compareAndSet(false, true)) {
      return;
    }
    Description failureDescription = failure.getDescription();
    if (!JUnitValidator.validateDescription(failureDescription)) {
      // The call stack from failure.getException() will be logged by the LogRunListener; look for
      // the "TestRunner" tag in the logcat.
      Log.w(
          TAG,
          "testFailure: JUnit reported "
              + failureDescription.getClassName()
              + "#"
              + failureDescription.getMethodName()
              + "; discarding as bogus.");
      return;
    }
    TestFailureEvent event;
    try {
      event =
          new TestFailureEvent(getTestCaseFromDescription(failureDescription), getFailure(failure));
    } catch (TestEventException e) {
      Log.d(TAG, "Unable to determine test case from failure [" + failure + "]", e);
      event = getTestFailureEventFromCachedDescription(failure);
      if (event == null) {
        return;
      }
    }
    try {
      notificationService.send(event);
    } catch (TestEventException e) {
      throw new IllegalStateException("Unable to send TestFailureEvent, terminating", e);
    }
  }

  @Nullable
  private TestFailureEvent getTestFailureEventFromCachedDescription(@NonNull Failure failure) {
    checkNotNull(failure, "failure cannot be null");
    TestCaseInfo testCase;
    try {
      // Get the testCase from the cached description instead.
      testCase = getTestCaseFromDescription(description);
    } catch (TestEventException ex) {
      Log.e(TAG, "Unable to determine test case from description [" + description + "]", ex);
      return null;
    }
    return new TestFailureEvent(
        testCase,
        new FailureInfo(
            failure.getMessage(), failure.getTestHeader(), failure.getTrace(), testCase));
  }

  /** {@inheritDoc} */
  @Override
  public void testAssumptionFailure(Failure failure) {
    try {
      notificationService.send(
          new TestAssumptionFailureEvent(
              getTestCaseFromDescription(failure.getDescription()), getFailure(failure)));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestAssumptionFailureEvent to Orchestrator", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void testIgnored(Description description) {
    try {
      TestCaseInfo info = getTestCaseFromDescription(description);
      Log.i(
          TAG,
          "TestIgnoredEvent("
              + description.getDisplayName()
              + "): "
              + description.getClassName()
              + "#"
              + description.getMethodName()
              + " = "
              + info.getClassAndMethodName());
      notificationService.send(new TestIgnoredEvent(info));
    } catch (TestEventException e) {
      Log.e(TAG, "Unable to send TestIgnoredEvent to Orchestrator", e);
    }
  }

  /** Reports the process crash event with a given exception. */
  public void reportProcessCrash(Throwable t, long timeoutMillis) {
    // Waits until the orchestrator gets a chance to handle the test failure (if any) before
    // bringing down the entire Instrumentation process.
    //
    // It's also possible that the process crashes in the middle of a test, so no TestFinish event
    // will be received. In this case, it will wait until timeoutMillis is reached.
    waitUntilTestFinished(timeoutMillis);

    Log.i(TAG, "Instrumentation process crashed, reporting failures to TestRunService.");
    reportProcessCrash(t);
  }

  /** Reports the process crash event with a given exception. */
  private void reportProcessCrash(Throwable t) {
    Failure failure = new Failure(description, t);
    testFailure(failure);
    testFinished(description);
    // The instrumentation is about to exit, let dependencies know we are completely finished.
    testRunCrashed(failure);
  }

  /**
   * Blocks until the test running within this Instrumentation has finished, whether the test
   * succeeds or fails.
   *
   * <p>We consider a test finished when the {@link #testFinished(Description)} method has been
   * called.
   */
  private void waitUntilTestFinished(long timeoutMillis) {
    if (!testFinishedCondition.block(timeoutMillis)) {
      Log.w(TAG, "Timeout waiting for the test to finish");
    }
  }
}
