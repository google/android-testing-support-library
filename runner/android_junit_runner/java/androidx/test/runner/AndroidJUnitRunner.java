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

package androidx.test.runner;

import static androidx.test.internal.util.ReflectionUtil.reflectivelyInvokeRemoteMethod;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.internal.events.client.TestEventClient;
import androidx.test.internal.events.client.TestEventClientArgs;
import androidx.test.internal.events.client.TestEventClientConnectListener;
import androidx.test.internal.platform.tracker.AnalyticsBasedUsageTracker;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry.AxtVersions;
import androidx.test.internal.runner.RunnerArgs;
import androidx.test.internal.runner.TestExecutor;
import androidx.test.internal.runner.TestRequestBuilder;
import androidx.test.internal.runner.listener.ActivityFinisherRunListener;
import androidx.test.internal.runner.listener.CoverageListener;
import androidx.test.internal.runner.listener.DelayInjector;
import androidx.test.internal.runner.listener.InstrumentationResultPrinter;
import androidx.test.internal.runner.listener.LogRunListener;
import androidx.test.internal.runner.listener.SuiteAssignmentPrinter;
import androidx.test.orchestrator.callback.OrchestratorV1Connection;
import androidx.test.runner.lifecycle.ApplicationLifecycleCallback;
import androidx.test.runner.lifecycle.ApplicationLifecycleMonitorRegistry;
import androidx.test.runner.screenshot.ScreenCaptureProcessor;
import androidx.test.runner.screenshot.Screenshot;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.junit.runner.Request;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.RunnerBuilder;

/**
 * An {@link Instrumentation} that runs JUnit3 and JUnit4 tests against an Android package
 * (application).
 *
 * <p>Based on and replacement for {@link android.test.InstrumentationTestRunner}. Supports a
 * superset of {@link android.test.InstrumentationTestRunner} features, while maintaining
 * command/output format compatibility with that class.
 *
 * <h3>Typical Usage</h3>
 *
 * <p>Write JUnit3 style {@link junit.framework.TestCase}s and/or JUnit4 style <a
 * href="http://junit.org/javadoc/latest/org/junit/Test.html"><code>Test</code></a>s that perform
 * tests against the classes in your package. Make use of the {@link
 * androidx.test.InstrumentationRegistry} if needed.
 *
 * <p>In an appropriate AndroidManifest.xml, define an instrumentation with android:name set to
 * {@link androidx.test.runner.AndroidJUnitRunner} and the appropriate android:targetPackage set.
 *
 * <p>
 *
 * <h4>Execution options:</h4>
 *
 * <p><b>Running all tests:</b> adb shell am instrument -w
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests in a class:</b> adb shell am instrument -w -e class
 * com.android.foo.FooTest com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running a single test:</b> adb shell am instrument -w -e class
 * com.android.foo.FooTest#testFoo com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests in multiple classes:</b> adb shell am instrument -w -e class
 * com.android.foo.FooTest,com.android.foo.TooTest
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests except those in a particular class:</b> adb shell am instrument -w -e
 * notClass com.android.foo.FooTest com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all but a single test:</b> adb shell am instrument -w -e notClass
 * com.android.foo.FooTest#testFoo com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests listed in a file:</b> adb shell am instrument -w -e testFile
 * /sdcard/tmp/testFile.txt com.android.foo/com.android.test.runner.AndroidJUnitRunner The file
 * should contain a list of line separated package names or test classes and optionally methods.
 * Valid package names consist of one or more java identifiers delimited by the '.' character, in
 * which the first character of the last identifier is not a capitalized letter. Valid class names
 * consist of one or more java identifiers delimited by the '.' character, in which the first
 * character of the last identifier is a capitalized letter. Valid method names are valid class
 * names with a '#' character and an additional java identifier appended to the end. (expected class
 * format: com.android.foo.FooClassName#testMethodName) (expected package format: com.android.foo)
 *
 * <p><b>Running all tests not listed in a file:</b> adb shell am instrument -w -e notTestFile
 * /sdcard/tmp/notTestFile.txt com.android.foo/com.android.test.runner.AndroidJUnitRunner The file
 * should contain a list of line separated package names or test classes and optionally methods.
 * Valid package names consist of one or more java identifiers delimited by the '.' character, in
 * which the first character of the last identifier is not a capitalized letter. Valid class names
 * consist of one or more java identifiers delimited by the '.' character, in which the first
 * character of the last identifier is a capitalized letter. Valid method names are valid class
 * names with a '#' character and an additional java identifier appended to the end. (expected class
 * format: com.android.foo.FooClassName#testMethodName) (expected package format: com.android.foo)
 *
 * <p><b>Running all tests in a java package:</b> adb shell am instrument -w -e package
 * com.android.foo.bar com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests except a particular package:</b> adb shell am instrument -w -e notPackage
 * com.android.foo.bar com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Running all tests matching a given regular expression:</b> adb shell am instrument -w -e
 * tests_regex BarTest.* com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>To debug your tests, set a break point in your code and pass:</b> -e debug true
 *
 * <p><b>Running a specific test size i.e. annotated with {@link SmallTest} or {@link MediumTest} or
 * {@link LargeTest}:</b> adb shell am instrument -w -e size [small|medium|large]
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Filter test run to tests with given annotation:</b> adb shell am instrument -w -e
 * annotation com.android.foo.MyAnnotation com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p>If used with other options, the resulting test run will contain the intersection of the two
 * options. e.g. "-e size large -e annotation com.android.foo.MyAnnotation" will run only tests with
 * both the {@link LargeTest} and "com.android.foo.MyAnnotation" annotations.
 *
 * <p><b>Filter test run to tests <i>with all</i> annotations in a list:</b> adb shell am instrument
 * -w -e annotation com.android.foo.MyAnnotation,com.android.foo.AnotherAnnotation
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Filter test run to tests <i>without</i> given annotation:</b> adb shell am instrument -w -e
 * notAnnotation com.android.foo.MyAnnotation
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p>As above, if used with other options, the resulting test run will contain the intersection of
 * the two options. e.g. "-e size large -e notAnnotation com.android.foo.MyAnnotation" will run
 * tests with the {@link LargeTest} annotation that do NOT have the "com.android.foo.MyAnnotation"
 * annotations.
 *
 * <p><b>Filter test run to tests <i>without any</i> of a list of annotations:</b> adb shell am
 * instrument -w -e notAnnotation com.android.foo.MyAnnotation,com.android.foo.AnotherAnnotation
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Filter test run to tests that pass all of a list of custom {@link Filter filter(s)}:</b>
 * adb shell am instrument -w -e filter
 * com.android.foo.MyCustomFilter,com.android.foo.AnotherCustomFilter
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p>A {@link Filter} class provided to the {@code filter} option must be public and must provide a
 * public constructor of one of the following patterns. They are searched in order and the first one
 * found is the one that is used.
 *
 * <ol>
 *   <li>{@code <init>()} - a no arguments constructor. This is for filters whose behavior is hard
 *       coded.
 *   <li>{@code <init>(Bundle bundle} - accepts a {@link Bundle} that contains the options passed to
 *       this instance. This is for filters whose behavior needs to be configured through additional
 *       options to {@code am instrument}.
 * </ol>
 *
 * <p><b>Filter test run to a shard of all tests, where numShards is an integer greater than 0 and
 * shardIndex is an integer between 0 (inclusive) and numShards (exclusive):</b> adb shell am
 * instrument -w -e numShards 4 -e shardIndex 1
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p><b>Use custom {@link RunnerBuilder builders} to run test classes:</b> adb shell am instrument
 * -w -e runnerBuilder com.android.foo.MyCustomBuilder,com.android.foo.AnotherCustomBuilder
 * com.android.foo/androidx.test.runner.AndroidJUnitRunner
 *
 * <p>A {@link RunnerBuilder} class provided to the {@code runnerBuilder} option must be public and
 * must provide a public no-argument constructor.
 *
 * <p><b>To run in 'log only' mode</b> -e log true This option will load and iterate through all
 * test classes and methods, but will bypass actual test execution. Useful for quickly obtaining
 * info on the tests to be executed by an instrumentation command.
 *
 * <p><b>To generate code coverage files (*.ec) that can be used by EMMA or JaCoCo:</b> -e coverage
 * true Note: For this to work, your classes have to be instrumented offline (i.e. at build time) by
 * EMMA/JaCoCo. By default, the code coverage results file will be saved in a
 * /data/data/<app>/files/coverage.ec file, unless overridden by coverageFile flag (see below)
 *
 * <p><b> To specify EMMA or JaCoCo code coverage results file path:</b> -e coverage true -e
 * coverageFile /sdcard/myFile.ec
 *
 * <p><b> To specify one or more <a
 * href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html"><code>
 * RunListener</code></a>s to observe the test run:</b> -e listener
 * com.foo.Listener,com.foo.Listener2
 *
 * <p><b> To use the new order of <a
 * href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html"><code>
 * RunListener</code></a>s during a test run: </b> -e newRunListenerMode true
 *
 * <p>New order of listeners guarantee that user defined <a
 * href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html"><code>
 * RunListener</code></a>s will be running before any of the default listeners defined in this
 * runner. Legacy order had those user defined listeners running after the default ones.
 *
 * <p></b>Note:</b>The new order will become the default in the future.
 *
 * <p><a
 * href="http://junit.org/javadoc/latest/org/junit/runner/notification/RunListener.html"><code>
 *    can also be specified via java.util.ServiceLoader metadata.
 *
 * <p><b> To specify a custom {@link java.lang.ClassLoader} to load the test class: </b> -e
 * classLoader com.foo.CustomClassLoader
 *
 * <p><b>Set timeout (in milliseconds) that will be applied to each test:</b> -e timeout_msec 5000
 *
 * <p>Supported for both JUnit3 and JUnit4 style tests. For JUnit3 tests, this flag is the only way
 * to specify timeouts. For JUnit4 tests, this flag is only supported when using the
 * the {@link androidx.test.ext.junit.runners.AndroidJUnit4) runner. It overrides timeouts
 * specified via <a
 * href="http://junit.org/javadoc/latest/org/junit/rules/Timeout.html"><code>org.junit.rules.Timeout
 * </code></a>. Please note that in JUnit4 <a
 * href="http://junit.org/javadoc/latest/org/junit/Test.html#timeout()"><code>
 * org.junit.Test#timeout()</code></a> annotation will take precedence over both, this flag and <a
 * href="http://junit.org/javadoc/latest/org/junit/rules/Timeout.html"><code>org.junit.rules.Timeout
 * </code></a> rule.
 *
 * <p><b>To disable Google Analytics:</b> -e disableAnalytics true
 *
 * <p>In order to make sure we are on the right track with each new release, the test runner
 * collects analytics. More specifically, it uploads a hash of the package name of the application
 * under test for each invocation. This allows us to measure both the count of unique packages using
 * this library as well as the volume of usage.
 *
 * <p><b>(Beta)To specify a custom {@link androidx.test.runner.screenshot.ScreenCaptureProcessor} to
 * use when processing a {@link androidx.test.runner.screenshot.ScreenCapture} produced by {@link
 * androidx.test.runner.screenshot.Screenshot#capture}</b>: -e screenCaptureProcessors
 * com.foo.Processor,com.foo.Processor2
 *
 * <p>If no {@link androidx.test.runner.screenshot.ScreenCaptureProcessor} is provided then the
 * {@link androidx.test.runner.screenshot.BasicScreenCaptureProcessor} is used. If one or more are
 * provided the {@link androidx.test.runner.screenshot.BasicScreenCaptureProcessor} is not used
 * unless it is one of the ones provided.
 *
 * <p><b>(Beta) To specify a remote static method for the runner to attempt to call reflectively:
 * </b> adb shell am instrument -w -e remoteMethod com.foo.bar#init
 *
 * <p><b>Note:</b> The method must be static. Usually used to initiate a remote testing client that
 * depends on the runner (e.g. Espresso).
 *
 * <p><b>All arguments can also be specified in the in the AndroidManifest via a meta-data tag:</b>
 *
 * <p>eg. using listeners:
 *
 * <pre>{@code
 * <instrumentation
 *    android:name="androidx.test.runner.AndroidJUnitRunner"
 *    android:targetPackage="com.foo.Bar">
 *    <meta-data
 *        android:name="listener"
 *        android:value="com.foo.Listener,com.foo.Listener2" />
 * </instrumentation>
 * }</pre>
 *
 * Arguments specified via shell will override manifest specified arguments.
 */
public class AndroidJUnitRunner extends MonitoringInstrumentation
    implements TestEventClientConnectListener {

  private static final String LOG_TAG = "AndroidJUnitRunner";

  private static final long MILLIS_TO_WAIT_FOR_TEST_FINISH = TimeUnit.SECONDS.toMillis(20);

  private Bundle arguments;
  private InstrumentationResultPrinter instrumentationResultPrinter =
      new InstrumentationResultPrinter();
  private RunnerArgs runnerArgs;
  private UsageTrackerFacilitator usageTrackerFacilitator;
  private TestEventClient testEventClient = TestEventClient.NO_OP_CLIENT;

  /** {@inheritDoc} */
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    this.arguments = arguments;
    parseRunnerArgs(this.arguments);

    if (waitForDebugger(runnerArgs)) {
      Log.i(LOG_TAG, "Waiting for debugger to connect...");
      Debug.waitForDebugger();
      Log.i(LOG_TAG, "Debugger connected.");
    }

    // We are only interested in tracking usage of the primary process.
    if (isPrimaryInstrProcess(runnerArgs.targetProcess)) {
      usageTrackerFacilitator = new UsageTrackerFacilitator(runnerArgs);
    } else {
      usageTrackerFacilitator = new UsageTrackerFacilitator(false);
    }

    for (ApplicationLifecycleCallback listener : runnerArgs.appListeners) {
      ApplicationLifecycleMonitorRegistry.getInstance().addLifecycleCallback(listener);
    }

    addScreenCaptureProcessors(runnerArgs);

    if (isOrchestratorServiceProvided()) {
      Log.v(LOG_TAG, "Waiting to connect to the Orchestrator service...");
    } else {
      // If no orchestration service is given, or we are not the primary process we can
      // start() immediately.
      start();
    }
  }

  /**
   * Connects to the remote test event service if necessary, i.e. either the test discovery or test
   * run events service is being used and this is the primary process.
   *
   * @return true if running in "orchestrated mode".
   */
  private boolean isOrchestratorServiceProvided() {
    TestEventClientArgs args =
        TestEventClientArgs.builder()
            .setConnectionFactory(OrchestratorV1Connection::new)
            .setOrchestratorService(runnerArgs.orchestratorService)
            .setPrimaryInstProcess(isPrimaryInstrProcess(runnerArgs.targetProcess))
            // The listTestsForOrchestrator arg is used for Orchestrator V1 connections:
            .setTestDiscoveryRequested(runnerArgs.listTestsForOrchestrator)
            .setTestRunEventsRequested(!runnerArgs.listTestsForOrchestrator)
            // The testDiscoveryService and testRunEventsService args are used for Orchestrator V2:
            .setTestDiscoveryService(runnerArgs.testDiscoveryService)
            .setTestRunEventService(runnerArgs.testRunEventsService)
            .build();
    testEventClient = TestEventClient.connect(getContext(), this, args);
    return testEventClient.isTestDiscoveryEnabled() || testEventClient.isTestRunEventsEnabled();
  }

  /** Checks if need to wait for debugger. */
  private boolean waitForDebugger(RunnerArgs arguments) {
    return arguments.debug && !arguments.listTestsForOrchestrator;
  }

  /**
   * Called when AndroidJUnitRunner connects to a test orchestrator, if the {@code
   * orchestratorService} parameter is set.
   *
   * @deprecated use onTestEventClientConnect()
   * @hide
   */
  @Deprecated // TODO(b/161833844): Remove onOrchestratorConnect(), use onTestEventClientConnect()
  public void onOrchestratorConnect() {
    onTestEventClientConnect();
  }

  /**
   * Called when AndroidJUnitRunner connects to a test orchestrator, if the {@code
   * orchestratorService}, {@code discoveryService} or {@code testRunEventService} parameter is set.
   *
   * @hide
   */
  @Override
  public void onTestEventClientConnect() {
    start();
  }

  /**
   * Build the arguments.
   *
   * <p>Read from manifest first so manifest-provided args can be overridden with command line
   * arguments
   *
   * @param arguments
   */
  private void parseRunnerArgs(Bundle arguments) {
    runnerArgs = new RunnerArgs.Builder().fromManifest(this).fromBundle(this, arguments).build();
  }

  /**
   * Get the Bundle object that contains the arguments passed to the instrumentation
   *
   * @return the Bundle object
   */
  private Bundle getArguments() {
    return arguments;
  }

  @VisibleForTesting
  InstrumentationResultPrinter getInstrumentationResultPrinter() {
    return instrumentationResultPrinter;
  }

  @Override
  public void onStart() {
    setJsBridgeClassName("androidx.test.espresso.web.bridge.JavaScriptBridge");
    super.onStart();
    Request testRequest = buildRequest(runnerArgs, getArguments());

    /*
     * The orchestrator cannot collect the list of tests as it is running in a different process
     * than the test app.  On first run, the Orchestrator will ask AJUR to list the tests
     * out that would be run for a given class parameter.  AJUR will then be successively
     * called with whatever it passes back to the orchestrator service.
     */
    if (testEventClient.isTestDiscoveryEnabled()) {
      testEventClient.addTests(testRequest.getRunner().getDescription());
      finish(Activity.RESULT_OK, new Bundle());
      return;
    }

    if (runnerArgs.remoteMethod != null) {
      reflectivelyInvokeRemoteMethod(
          runnerArgs.remoteMethod.testClassName, runnerArgs.remoteMethod.methodName);
    }

    // TODO(b/162075422): using deprecated isPrimaryInstrProcess(argsProcessName) method
    if (!isPrimaryInstrProcess(runnerArgs.targetProcess)) {
      Log.i(LOG_TAG, "Runner is idle...");
      return;
    }

    Bundle results = new Bundle();
    try {
      TestExecutor.Builder executorBuilder = new TestExecutor.Builder(this);
      addListeners(runnerArgs, executorBuilder);
      results = executorBuilder.build().execute(testRequest);
    } catch (RuntimeException e) {
      final String msg = "Fatal exception when running tests";
      Log.e(LOG_TAG, msg, e);
      // report the exception to instrumentation out
      results.putString(
          Instrumentation.REPORT_KEY_STREAMRESULT, msg + "\n" + Log.getStackTraceString(e));
    }
    finish(Activity.RESULT_OK, results);
  }

  @Override
  public void finish(int resultCode, Bundle results) {
    try {
      usageTrackerFacilitator.trackUsage("AndroidJUnitRunner", AxtVersions.RUNNER_VERSION);
      usageTrackerFacilitator.sendUsages();
    } catch (RuntimeException re) {
      Log.w(LOG_TAG, "Failed to send analytics.", re);
    }
    super.finish(resultCode, results);
  }

  @VisibleForTesting
  final void addListeners(RunnerArgs args, TestExecutor.Builder builder) {
    if (args.newRunListenerMode) {
      addListenersNewOrder(args, builder);
    } else {
      addListenersLegacyOrder(args, builder);
    }
  }

  private void addListenersLegacyOrder(RunnerArgs args, TestExecutor.Builder builder) {
    if (args.logOnly) {
      // Only add the listener that will report the list of tests when running in logOnly
      // mode.
      builder.addRunListener(getInstrumentationResultPrinter());
    } else if (args.suiteAssignment) {
      builder.addRunListener(new SuiteAssignmentPrinter());
    } else {
      builder.addRunListener(new LogRunListener());
      if (testEventClient.isTestRunEventsEnabled()) {
        builder.addRunListener(testEventClient.getNotificationRunListener());
      } else {
        builder.addRunListener(getInstrumentationResultPrinter());
      }

      if (shouldWaitForActivitiesToComplete()) {
        builder.addRunListener(
            new ActivityFinisherRunListener(
                this,
                new MonitoringInstrumentation.ActivityFinisher(),
                new Runnable() {
                  // Yes, this is terrible and weird but avoids adding a new public API
                  // outside the internal package.
                  @Override
                  public void run() {
                    waitForActivitiesToComplete();
                  }
                }));
      }
      addDelayListener(args, builder);
      addCoverageListener(args, builder);
    }
    addListenersFromClasspath(builder);
    addListenersFromArg(args, builder);
  }

  private void addListenersNewOrder(RunnerArgs args, TestExecutor.Builder builder) {
    // User defined listeners go first, to guarantee running before InstrumentationResultPrinter
    // and ActivityFinisherRunListener. Delay and Coverage Listener are also moved before for the
    // same reason.
    addListenersFromClasspath(builder);
    addListenersFromArg(args, builder);
    if (args.logOnly) {
      // Only add the listener that will report the list of tests when running in logOnly
      // mode.
      builder.addRunListener(getInstrumentationResultPrinter());
    } else if (args.suiteAssignment) {
      builder.addRunListener(new SuiteAssignmentPrinter());
    } else {
      builder.addRunListener(new LogRunListener());
      addDelayListener(args, builder);
      addCoverageListener(args, builder);
      if (testEventClient.isTestRunEventsEnabled()) {
        builder.addRunListener(testEventClient.getNotificationRunListener());
      } else {
        builder.addRunListener(getInstrumentationResultPrinter());
      }
      if (shouldWaitForActivitiesToComplete()) {
        builder.addRunListener(
            new ActivityFinisherRunListener(
                this,
                new MonitoringInstrumentation.ActivityFinisher(),
                new Runnable() {
                  // Yes, this is terrible and weird but avoids adding a new public API
                  // outside the internal package.
                  @Override
                  public void run() {
                    waitForActivitiesToComplete();
                  }
                }));
      }
    }
  }

  private void addScreenCaptureProcessors(RunnerArgs args) {
    Screenshot.addScreenCaptureProcessors(
        new HashSet<ScreenCaptureProcessor>(args.screenCaptureProcessors));
  }

  private void addCoverageListener(RunnerArgs args, TestExecutor.Builder builder) {
    if (args.codeCoverage) {
      builder.addRunListener(
          new CoverageListener(args.codeCoveragePath, args.isTestServiceAvailable));
    }
  }

  /** Sets up listener to inject a delay between each test, if specified. */
  private void addDelayListener(RunnerArgs args, TestExecutor.Builder builder) {
    if (args.delayInMillis > 0) {
      builder.addRunListener(new DelayInjector(args.delayInMillis));
    } else if (args.logOnly && Build.VERSION.SDK_INT < 16) {
      // On older platforms, collecting tests can fail for large volume of tests.
      // Insert a small delay between each test to prevent this
      builder.addRunListener(new DelayInjector(15 /* msec */));
    }
  }

  /** Load and register {@link RunListener}'s specified via {@link java.util.ServiceLoader}. */
  private static void addListenersFromClasspath(TestExecutor.Builder builder) {
    for (RunListener listener : ServiceLoader.load(RunListener.class)) {
      builder.addRunListener(listener);
    }
  }

  private void addListenersFromArg(RunnerArgs args, TestExecutor.Builder builder) {
    for (RunListener listener : args.listeners) {
      builder.addRunListener(listener);
    }
  }

  @Override
  public boolean onException(Object obj, Throwable e) {
    Log.e(LOG_TAG, "An unhandled exception was thrown by the app.");
    InstrumentationResultPrinter instResultPrinter = getInstrumentationResultPrinter();
    if (instResultPrinter != null) {
      // Report better error message back to Instrumentation results.
      instResultPrinter.reportProcessCrash(e);
    }
    if (testEventClient.isTestRunEventsEnabled()) {
      // Report the error message back to the orchestrator.
      testEventClient.reportProcessCrash(e, MILLIS_TO_WAIT_FOR_TEST_FINISH);
    }
    Log.i(LOG_TAG, "Bringing down the entire Instrumentation process.");
    return super.onException(obj, e);
  }

  /** Builds a {@link Request} based on given input arguments. */
  @VisibleForTesting
  Request buildRequest(RunnerArgs runnerArgs, Bundle bundleArgs) {

    TestRequestBuilder builder = createTestRequestBuilder(this, bundleArgs);
    builder.addPathsToScan(runnerArgs.classpathToScan);
    if (runnerArgs.classpathToScan.isEmpty()) {
      // Only scan for tests for current apk aka testContext
      // Note that this represents a change from InstrumentationTestRunner where
      // getTargetContext().getPackageCodePath() aka app under test was also scanned
      // Only add the package classpath when no custom classpath is provided in order to
      // avoid duplicate class issues.
      builder.addPathToScan(getContext().getPackageCodePath());
    }
    builder.addFromRunnerArgs(runnerArgs);

    registerUserTracker();

    return builder.build();
  }

  private void registerUserTracker() {
    Context targetContext = getTargetContext();
    if (targetContext != null) {
      usageTrackerFacilitator.registerUsageTracker(
          new AnalyticsBasedUsageTracker.Builder(targetContext).buildIfPossible());
    }
  }

  /** Factory method for {@link TestRequestBuilder}. */
  TestRequestBuilder createTestRequestBuilder(Instrumentation instr, Bundle arguments) {
    return new TestRequestBuilder(instr, arguments);
  }
}
