/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.test.orchestrator.listeners;

import androidx.test.orchestrator.junit.ParcelableDescription;
import androidx.test.orchestrator.junit.ParcelableFailure;
import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Reimplementation of org.junit.TextListener that accepts {@link
 * androidx.test.orchestrator.AndroidTestOrchestrator} compatible objects.
 *
 * <p>{@link OrchestrationResultPrinter} is a near line by line copy of
 * InstrumentationResultPrinter, and since InstrumentationResultPrinter relies on
 * org.junit.internal.TextListener, this class exists for the {@link OrchestrationResultPrinter} to
 * rely on, until such time as we can resolve b/35394729 and unify their expertise.
 */
public class TextListener
    extends androidx.test.orchestrator.listeners.OrchestrationRunListener {

  private final PrintStream writer;

  public TextListener(PrintStream writer) {
    this.writer = writer;
  }

  public void testRunFinished(OrchestrationResult result) {
    printHeader(result.getRunTime());
    printFailures(result);
    printFooter(result);
  }

  @Override
  public void testStarted(ParcelableDescription description) {
    writer.append('.');
  }

  @Override
  public void testFailure(ParcelableFailure failure) {
    writer.append('E');
  }

  @Override
  public void testIgnored(ParcelableDescription description) {
    writer.append('I');
  }

  private PrintStream getWriter() {
    return writer;
  }

  protected void printHeader(long runTime) {
    getWriter().println();
    getWriter().println("Time: " + elapsedTimeAsString(runTime));
  }

  protected void printFailures(OrchestrationResult result) {
    List<ParcelableFailure> failures = result.getFailures();
    if (failures.isEmpty()) {
      return;
    }
    if (failures.size() == 1) {
      getWriter().println("There was " + failures.size() + " failure:");
    } else {
      getWriter().println("There were " + failures.size() + " failures:");
    }
    int i = 1;
    for (ParcelableFailure each : failures) {
      printFailure(each, "" + i++);
    }
  }

  protected void printFailure(ParcelableFailure each, String prefix) {
    getWriter().println(prefix + ") " + each.getDescription().getDisplayName());
    getWriter().print(each.getTrace());
  }

  protected void printFooter(OrchestrationResult result) {
    if (result.wasSuccessful()) {
      getWriter().println();
      getWriter().print("OK");
      getWriter()
          .println(
              " (" + result.getRunCount() + " test" + (result.getRunCount() == 1 ? "" : "s") + ")");

    } else {
      getWriter().println();
      getWriter().println("FAILURES!!!");
      getWriter()
          .println(
              "Tests found: "
                  + result.getExpectedCount()
                  + ", Tests run: "
                  + result.getRunCount()
                  + ",  Failures: "
                  + result.getFailureCount());
    }
    getWriter().println();
  }

  /** Returns the formatted string of the elapsed time. Duplicated from BaseTestRunner. Fix it. */
  protected String elapsedTimeAsString(long runTime) {
    // Use Locale.US so that instrumentation parsers can parse it correctly.
    return NumberFormat.getInstance(Locale.US).format((double) runTime / 1000);
  }
}
