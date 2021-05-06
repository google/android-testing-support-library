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

package androidx.test.orchestrator;

import static androidx.test.orchestrator.OrchestratorConstants.AJUR_CLASS_ARGUMENT;
import static androidx.test.orchestrator.OrchestratorConstants.AJUR_COVERAGE;
import static androidx.test.orchestrator.OrchestratorConstants.AJUR_COVERAGE_FILE;
import static androidx.test.orchestrator.OrchestratorConstants.AJUR_DISABLE_ANALYTICS;
import static androidx.test.orchestrator.OrchestratorConstants.CLEAR_PKG_DATA;
import static androidx.test.orchestrator.OrchestratorConstants.COVERAGE_FILE_PATH;
import static androidx.test.orchestrator.OrchestratorConstants.ISOLATED_ARGUMENT;
import static androidx.test.orchestrator.OrchestratorConstants.ORCHESTRATOR_DEBUG_ARGUMENT;
import static androidx.test.orchestrator.OrchestratorConstants.TARGET_INSTRUMENTATION_ARGUMENT;
import static com.google.common.base.Preconditions.checkState;

import android.Manifest.permission;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.test.internal.platform.tracker.AnalyticsBasedUsageTracker;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry.AxtVersions;
import androidx.test.orchestrator.TestRunnable.RunFinishedListener;
import androidx.test.orchestrator.junit.ParcelableDescription;
import androidx.test.orchestrator.listeners.OrchestrationListenerManager;
import androidx.test.orchestrator.listeners.OrchestrationResult;
import androidx.test.orchestrator.listeners.OrchestrationResultPrinter;
import androidx.test.orchestrator.listeners.OrchestrationXmlTestRunListener;
import androidx.test.runner.UsageTrackerFacilitator;
import androidx.test.services.shellexecutor.ClientNotConnected;
import androidx.test.services.shellexecutor.ShellExecSharedConstants;
import androidx.test.services.shellexecutor.ShellExecutor;
import androidx.test.services.shellexecutor.ShellExecutorImpl;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * An {@link Instrumentation} that executes other instrumentations.
 *
 * <p>Takes parameters {@code targetPackage} and {@code targetInstrumentation}, and executes that
 * instrumentation with the same class parameters.
 *
 * <p>When run normally (i.e. without setting the isolated flag to false) the on device orchestrator
 * will handle test collection and execution. The target instrumentation is executed via shell
 * commands on the device, with one shell command for test collection, followed by one shell command
 * per test.
 *
 * <p>Each test runs in its own isolated process with its own instrumentation.
 *
 * <h3>Setup</h3>
 *
 * <p>The AndroidTestOrchestrator requires installation of a test services APK {@code
 * androidx.test.services}, and the stubapp APK {@code androidx.test.orchestrator.stubapp}. The
 * orchestrator is technically instrumenting the stubapp, but it's real purpose is to issue commands
 * to {@link androidx.test.runner.AndroidJUnitRunner} or another instrumentation, to run your tests.
 *
 * <h3>Typical usage</h3>
 *
 * <p>Whereas previously you might have called {@code am instrument -w
 * com.example.app/androidx.test.runner.AndroidJUnitRunner} you would now execute {@code
 * 'CLASSPATH=$(pm path androidx.test.services) app_process /
 * androidx.test.services.shellexecutor.ShellMain am instrument -w -e targetInstrumentation
 * com.example.app/androidx.test.runner.AndroidJUnitRunner
 * androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator'}
 *
 * <h4>Execution options:</h4>
 *
 * <p>All flags besides the ones listed below are passed by the orchestrator to the target
 * instrumentation.
 *
 * <p>Pass the {@code -e isolated false} flag if you wish the orchestrator to run all your tests in
 * a single process (as if you invoked the target instrumentation directly
 *
 * <p>Pass {@code -e coverage true -e coverageFilePath /sdcard/foo/} flag to generate coverage files
 * in the given location (The app must have permission to write to the given location). The coverage
 * file naming convention will look like this {@code com.foo.Class#method1.ec}. Note, this is only
 * supported when running in isolated mode. Also, it cannot be used together with
 * AndroidJUnitRunner's {@code coverageFile} flag. Since the generated coverage files will overwrite
 * each other.
 *
 * <p>Pass {@code -e clearPackageData} flag if you wish the orchestrator to run {@code pm clear
 * context.getPackageName()} and {@code pm clear targetContext.getPackageName()} commands in between
 * test invocations. Note, the context in the clear command is the App under test context.
 *
 * <p>Pass {@code -e orchestratorDebug} flag if you need to debug orchestrator itself. Note, to
 * debug test code you still need to pass {@code -e debug}.
 */
public final class AndroidTestOrchestrator extends android.app.Instrumentation
    implements RunFinishedListener {

  private static final String TAG = "AndroidTestOrchestrator";
  // As defined in the AndroidManifest of the Orchestrator app.
  private static final String ORCHESTRATOR_SERVICE_LOCATION = "OrchestratorService";
  private static final String ORCHESTRATOR_SERVICE_ARGUMENT = "orchestratorService";

  private static final String TEST_COLLECTION_FILENAME = "testCollection.txt";
  private static final String TEST_RUN_FILENAME = "%s.txt";

  private static final Pattern FULLY_QUALIFIED_CLASS_AND_METHOD =
      Pattern.compile("[\\w\\.?]+#\\w+");

  private static final List<String> RUNTIME_PERMISSIONS =
      Arrays.asList(permission.WRITE_EXTERNAL_STORAGE, permission.READ_EXTERNAL_STORAGE);

  private final OrchestrationXmlTestRunListener xmlTestRunListener =
      new OrchestrationXmlTestRunListener();
  private final OrchestrationResult.Builder resultBuilder = new OrchestrationResult.Builder();
  private final OrchestrationResultPrinter resultPrinter = new OrchestrationResultPrinter();
  private final OrchestrationListenerManager listenerManager =
      new OrchestrationListenerManager(this);

  private final ExecutorService executorService;

  // assigned on service connection callback thread, read from several other threads.
  private volatile CallbackLogic callbackLogic;

  private UsageTrackerFacilitator usageTrackerFacilitator;
  private Bundle arguments;

  // TODO(b/73548232) logic that touches these fields has nothing to do with being an
  // instrumentation, it should live in its own state machine class.
  private String test;
  private Iterator<String> testIterator;

  public AndroidTestOrchestrator() {
    super();
    // We never want to execute multiple tests in parallel.
    executorService =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread t = Executors.defaultThreadFactory().newThread(runnable);
              t.setName(TAG); // Required for TikTok to not kill the thread.
              return t;
            });
  }

  @Override
  public void onCreate(Bundle arguments) {
    // Wait for debugger if debug argument is passed
    if (debugOrchestrator(arguments)) {
      Log.i(TAG, "Waiting for debugger to connect to ATO...");
      Debug.waitForDebugger();
      Log.i(TAG, "Debugger connected.");
    }

    if (null == arguments.getString(TARGET_INSTRUMENTATION_ARGUMENT)) {
      throw new IllegalArgumentException("You must provide a target instrumentation.");
    }

    this.arguments = arguments;
    this.arguments.putString(ORCHESTRATOR_SERVICE_ARGUMENT, ORCHESTRATOR_SERVICE_LOCATION);

    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    super.onStart();
    try {
      registerUserTracker();
      grantRuntimePermissions(RUNTIME_PERMISSIONS);
      connectOrchestratorService();
    } catch (RuntimeException e) {
      final String msg = "Fatal exception when setting up.";
      Log.e(TAG, msg, e);
      // Report the startup exception to instrumentation out.
      Bundle failureBundle = createResultBundle();
      failureBundle.putString(
          Instrumentation.REPORT_KEY_STREAMRESULT, msg + "\n" + Log.getStackTraceString(e));
      finish(Activity.RESULT_OK, failureBundle);
    }
  }

  private void grantRuntimePermissions(List<String> permissions) {
    if (Build.VERSION.SDK_INT < 24) {
      // Only grant runtime permissions on API 24 and up
      return;
    }
    Context context = getContext();
    for (String permission : permissions) {
      if (PackageManager.PERMISSION_GRANTED == context.checkCallingOrSelfPermission(permission)) {
        continue;
      }
      // Fire and wait for the runtime permissions command.
      execShellCommandSync(
          context,
          getSecret(arguments),
          "pm",
          Arrays.asList("grant", context.getPackageName(), permission));
      if (PackageManager.PERMISSION_GRANTED != context.checkCallingOrSelfPermission(permission)) {
        throw new IllegalStateException("Permission requested but not granted!");
      }
    }
  }

  // Note: We connect to the orchestrator service mostly so that we can verify that it is up and
  // running, but communication between AndroidTestOrchestrator and the remote instrumentation
  // is done via executing shell commands.
  private void connectOrchestratorService() {
    Intent intent = new Intent(getContext(), OrchestratorService.class);
    getContext().bindService(intent, connection, Service.BIND_AUTO_CREATE);
  }

  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
          Log.i(TAG, "AndroidTestOrchestrator has connected to the orchestration service");
          callbackLogic = (CallbackLogic) service;
          callbackLogic.setListenerManager(listenerManager);
          collectTests();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
          Log.e(
              TAG,
              "AndroidTestOrchestrator has prematurely disconnected from the orchestration service,"
                  + "run cancelled.");
          finish(Activity.RESULT_CANCELED, createResultBundle());
        }
      };

  private void collectTests() {
    String classArg = arguments.getString(AJUR_CLASS_ARGUMENT);
    // If we are given a single, fully qualified test then there's no point in test collection.
    // Proceed as if we had done collection and gotten the single argument.
    if (isSingleMethodTest(classArg)) {
      Log.i(TAG, String.format("Single test parameter %s, skipping test collection", classArg));
      callbackLogic.addTest(classArg);
      runFinished();
    } else {
      Log.i(TAG, String.format("Multiple test parameter %s, starting test collection", classArg));
      executorService.execute(
          TestRunnable.testCollectionRunnable(
              getContext(),
              getSecret(arguments),
              arguments,
              getOutputStream(),
              AndroidTestOrchestrator.this));
    }
  }

  @VisibleForTesting
  static boolean isSingleMethodTest(String classArg) {
    if (TextUtils.isEmpty(classArg)) {
      return false;
    }
    return FULLY_QUALIFIED_CLASS_AND_METHOD.matcher(classArg).matches();
  }

  /** Invoked every time the TestRunnable finishes, including after test collection. */
  @Override
  public void runFinished() {
    // everything in this method should live in a different class to model the test execution state
    // machine. We do not need to have any association with Instrumentation beyond calling finish.
    // The first run complete will occur during test collection.
    if (null == test) {
      List<String> allTests = callbackLogic.provideCollectedTests();
      testIterator = allTests.iterator();
      addListeners(allTests.size());

      if (allTests.isEmpty()) {
        finish(Activity.RESULT_CANCELED, createResultBundle());
        return;
      }
    } else {
      listenerManager.testProcessFinished(getOutputFile());
    }

    if (runsInIsolatedMode(arguments)) {
      executeNextTest();
    } else {
      executeEntireTestSuite();
    }
  }

  private void executeEntireTestSuite() {
    if (null != test) {
      finish(Activity.RESULT_OK, createResultBundle());
      return;
    }

    // We don't actually need test to have any particular value,
    // just to indicate we've started execution.
    test = "";
    executorService.execute(
        TestRunnable.legacyTestRunnable(
            getContext(), getSecret(arguments), arguments, getOutputStream(), this));
  }

  private void executeNextTest() {
    if (!testIterator.hasNext()) {
      finish(Activity.RESULT_OK, createResultBundle());
      return;
    }
    test = testIterator.next();
    listenerManager.testProcessStarted(new ParcelableDescription(test));
    String coveragePath = addTestCoverageSupport(arguments, test);
    if (coveragePath != null) {
      arguments.putString(AJUR_COVERAGE_FILE, coveragePath);
    }
    clearPackageData();
    executorService.execute(
        TestRunnable.singleTestRunnable(
            getContext(), getSecret(arguments), arguments, getOutputStream(), this, test));
    if (coveragePath != null) {
      arguments.remove(AJUR_COVERAGE_FILE);
    }
  }

  private void clearPackageData() {
    if (!shouldClearPackageData(arguments)) {
      return;
    }
    executorService.execute(
        new Runnable() {
          @Override
          public void run() {
            execShellCommandSync(
                getContext(),
                getSecret(arguments),
                "pm",
                Arrays.asList("clear", getTargetPackage(arguments)));
            execShellCommandSync(
                getContext(),
                getSecret(arguments),
                "pm",
                Arrays.asList("clear", getTargetInstrPackage(arguments)));
          }
        });
  }

  @VisibleForTesting
  static String addTestCoverageSupport(Bundle args, String filename) {
    // Only do the aggregate coverage mode if coverage was requested AND we're running in isolation
    // mode.
    // If not running in isolation, the AJUR coverage mechanism of dumping coverage data to a
    // single file is sufficient since all test run in the same invocation.
    if (shouldRunCoverage(args) && runsInIsolatedMode(args)) {
      checkState(
          args.getString(AJUR_COVERAGE_FILE) == null,
          "Can't use a custom coverage file name [-e %s %s] when running through "
              + "orchestrator in isolated mode, since the generated coverage files will "
              + "overwrite each other. Please consider using [%s] instead.",
          AJUR_COVERAGE_FILE,
          args.getString(AJUR_COVERAGE_FILE),
          COVERAGE_FILE_PATH);

      String path = args.getString(COVERAGE_FILE_PATH);
      return path + filename + ".ec";
    }
    return null;
  }

  private OutputStream getOutputStream() {
    try {
      Context context = getContext();
      // Support for directBootMode
      if (Build.VERSION.SDK_INT >= 24) {
        context = ContextCompat.createDeviceProtectedStorageContext(context);
      }
      return context.openFileOutput(getOutputFile(), 0);
    } catch (FileNotFoundException e) {
      throw new RuntimeException("Could not open stream for output");
    }
  }

  private String getOutputFile() {
    if (null == test) {
      return TEST_COLLECTION_FILENAME;
    } else {
      return String.format(TEST_RUN_FILENAME, test);
    }
  }

  private void addListeners(int testSize) {
    listenerManager.addListener(xmlTestRunListener);
    listenerManager.addListener(resultBuilder);
    listenerManager.addListener(resultPrinter);
    listenerManager.orchestrationRunStarted(testSize);
  }

  private Bundle createResultBundle() {
    OutputStream stream = new ByteArrayOutputStream();
    PrintStream writer = new PrintStream(stream);
    Bundle bundle = new Bundle();

    try {
      resultBuilder.orchestrationRunFinished();
      resultPrinter.orchestrationRunFinished(writer, resultBuilder.build());
    } finally {
      writer.close();
    }

    bundle.putString(
        Instrumentation.REPORT_KEY_STREAMRESULT, String.format("\n%s", stream.toString()));
    return bundle;
  }

  @Override
  public void finish(int resultCode, Bundle results) {
    xmlTestRunListener.orchestrationRunFinished();
    try {
      usageTrackerFacilitator.trackUsage("AndroidTestOrchestrator", AxtVersions.RUNNER_VERSION);
      usageTrackerFacilitator.sendUsages();
    } catch (RuntimeException re) {
      Log.w(TAG, "Failed to send analytics.", re);
    } finally {
      try {
        super.finish(resultCode, results);
      } catch (SecurityException e) {
        Log.e(TAG, "Security exception thrown on shutdown", e);
        // On API Level 18 a security exception can be occasionally thrown when calling finish
        // with a result bundle taken from a remote message.  Recreating the result bundle and
        // retrying finish has a high probability of suppressing the flake.
        results = createResultBundle();
        super.finish(resultCode, results);
      }
    }
  }

  @Override
  public boolean onException(Object obj, Throwable e) {
    resultPrinter.reportProcessCrash(e);
    return super.onException(obj, e);
  }

  private static boolean runsInIsolatedMode(Bundle arguments) {
    // We run in isolated mode always, unless flag isolated is explicitly false.
    return !(Boolean.FALSE.toString().equalsIgnoreCase(arguments.getString(ISOLATED_ARGUMENT)));
  }

  private static boolean debugOrchestrator(Bundle arguments) {
    return Boolean.parseBoolean(arguments.getString(ORCHESTRATOR_DEBUG_ARGUMENT));
  }

  private static boolean shouldTrackUsage(Bundle arguments) {
    return !Boolean.parseBoolean(arguments.getString(AJUR_DISABLE_ANALYTICS));
  }

  private static boolean shouldRunCoverage(Bundle arguments) {
    // only run coverage if -e coverage true AND -e coverageFilePath are passed
    String path = arguments.getString(COVERAGE_FILE_PATH);
    return Boolean.parseBoolean(arguments.getString(AJUR_COVERAGE))
        && (path != null && !path.isEmpty());
  }

  private static boolean shouldClearPackageData(Bundle arguments) {
    return Boolean.parseBoolean(arguments.getString(CLEAR_PKG_DATA));
  }

  private static String getSecret(Bundle arguments) {
    String secret = arguments.getString(ShellExecSharedConstants.BINDER_KEY);
    if (null == secret) {
      throw new IllegalArgumentException(
          "Cannot find secret for ShellExecutor binder published at "
              + ShellExecSharedConstants.BINDER_KEY);
    }
    return secret;
  }

  private static String getTargetInstrumentation(Bundle arguments) {
    String targetInstr = arguments.getString(TARGET_INSTRUMENTATION_ARGUMENT);
    if (null == targetInstr) {
      throw new IllegalArgumentException(
          "You must provide a target instrumentation using the "
              + "following runner arg: "
              + TARGET_INSTRUMENTATION_ARGUMENT);
    }
    return targetInstr;
  }

  private void registerUserTracker() {
    usageTrackerFacilitator = new UsageTrackerFacilitator(shouldTrackUsage(arguments));
    Context targetContext = getTargetContext();
    if (targetContext != null) {
      usageTrackerFacilitator.registerUsageTracker(
          new AnalyticsBasedUsageTracker.Builder(targetContext)
              .withTargetPackage(getTargetInstrPackage(arguments))
              .buildIfPossible());
    }
  }

  private static String execShellCommandSync(
      Context context, String secret, String cmd, List<String> params) {
    String cmdResult = null;
    Throwable exception = null;
    //noinspection TryWithIdenticalCatches (not supported be below API lvl 19)
    try {
      ShellExecutor shellExecutor = new ShellExecutorImpl(context, secret);
      cmdResult = shellExecutor.executeShellCommandSync(cmd, params, new HashMap<>(), false);
    } catch (ClientNotConnected clientNotConnected) {
      exception = clientNotConnected;
    } catch (IOException e) {
      exception = e;
    } catch (RemoteException e) {
      exception = e;
    } finally {
      if (exception != null) {
        Log.w(
            TAG,
            String.format("Failed executing shell command [%s] with params [%s]", cmd, params),
            exception);
      }
    }
    return cmdResult;
  }

  /** Returns the instrumentation package of the app under test. */
  private static String getTargetInstrPackage(Bundle arguments) {
    return getTargetInstrumentation(arguments).split("/", -1)[0];
  }

  /** Returns the package of the app under test. */
  private String getTargetPackage(Bundle arguments) {
    String instrPackage = getTargetInstrPackage(arguments);
    String instrumentation = getTargetInstrumentation(arguments).split("/", -1)[1];
    PackageManager packageManager = getContext().getPackageManager();
    try {
      InstrumentationInfo instrInfo =
          packageManager.getInstrumentationInfo(
              new ComponentName(instrPackage, instrumentation), 0 /* no flags */);
      return instrInfo.targetPackage;
    } catch (NameNotFoundException e) {
      throw new IllegalStateException(
          "Package [" + instrPackage + "] cannot be found on the system.");
    }
  }
}
