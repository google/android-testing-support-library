/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static androidx.test.internal.util.Checks.checkNotMainThread;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue.IdleHandler;
import android.os.UserHandle;
import androidx.annotation.Nullable;
import android.util.Log;
import androidx.test.internal.platform.app.ActivityLifecycleTimeout;
import androidx.test.internal.runner.InstrumentationConnection;
import androidx.test.internal.runner.hidden.ExposedInstrumentationApi;
import androidx.test.internal.runner.intent.IntentMonitorImpl;
import androidx.test.internal.runner.intercepting.DefaultInterceptingActivityFactory;
import androidx.test.internal.runner.lifecycle.ActivityLifecycleMonitorImpl;
import androidx.test.internal.runner.lifecycle.ApplicationLifecycleMonitorImpl;
import androidx.test.internal.util.Checks;
import androidx.test.internal.util.ProcSummary;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.intent.IntentMonitorRegistry;
import androidx.test.runner.intent.IntentStubberRegistry;
import androidx.test.runner.intercepting.InterceptingActivityFactory;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.ApplicationLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.ApplicationStage;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An instrumentation that enables several advanced features and makes some hard guarantees about
 * the state of the application under instrumentation.
 *
 * <p>A short list of these capabilities:
 *
 * <ul>
 *   <li>Forces Application.onCreate() to happen before Instrumentation.onStart() runs (ensuring
 *       your code always runs in a sane state).
 *   <li>Logs application death due to exceptions.
 *   <li>Allows tracking of activity lifecycle states.
 *   <li>Registers instrumentation arguments in an easy to access place.
 *   <li>Ensures your activities are creating themselves in reasonable amounts of time.
 *   <li>Provides facilities to dump current app threads to test outputs.
 *   <li>Ensures all activities finish before instrumentation exits.
 * </ul>
 *
 * This Instrumentation is *NOT* a test instrumentation (some of its subclasses are). It makes no
 * assumptions about what the subclass wants to do.
 */
public class MonitoringInstrumentation extends ExposedInstrumentationApi {

  private static final String TAG = "MonitoringInstr";

  private static final long MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP = TimeUnit.SECONDS.toMillis(2);
  private static final long MILLIS_TO_POLL_FOR_ACTIVITY_STOP =
      MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP / 40;
  private ActivityLifecycleMonitorImpl lifecycleMonitor = new ActivityLifecycleMonitorImpl();
  private ApplicationLifecycleMonitorImpl applicationMonitor =
      new ApplicationLifecycleMonitorImpl();
  private IntentMonitorImpl intentMonitor = new IntentMonitorImpl();
  private ExecutorService executorService;
  private Handler handlerForMainLooper;
  private AtomicBoolean anActivityHasBeenLaunched = new AtomicBoolean(false);
  private AtomicLong lastIdleTime = new AtomicLong(0);
  private AtomicInteger startedActivityCounter = new AtomicInteger(0);
  private String jsBridgeClassName;
  private AtomicBoolean isJsBridgeLoaded = new AtomicBoolean(false);

  // read from many threads / written by many threads.
  // if null will be calculated in the same way by any thread.
  private volatile Boolean isOriginalInstr = null;

  private ThreadLocal<Boolean> isDexmakerClassLoaderInitialized = new ThreadLocal<>();

  private IdleHandler idleHandler =
      new IdleHandler() {
        @Override
        public boolean queueIdle() {
          lastIdleTime.set(System.currentTimeMillis());
          return true;
        }
      };

  private volatile boolean finished = false;
  private volatile InterceptingActivityFactory interceptingActivityFactory;
  private UncaughtExceptionHandler standardHandler;

  /**
   * Sets up lifecycle monitoring, and argument registry.
   *
   * <p>Subclasses must call up to onCreate(). This onCreate method does not call start() it is the
   * subclasses responsibility to call start if it desires.
   */
  @Override
  public void onCreate(Bundle arguments) {
    Log.i(TAG, "Instrumentation started!");
    logUncaughtExceptions();
    // Multidex must be installed early otherwise we could call into code that has
    // landed in a different dex split.
    installMultidex();

    InstrumentationRegistry.registerInstance(this, arguments);
    androidx.test.InstrumentationRegistry.registerInstance(this, arguments);
    ActivityLifecycleMonitorRegistry.registerInstance(lifecycleMonitor);
    ApplicationLifecycleMonitorRegistry.registerInstance(applicationMonitor);
    IntentMonitorRegistry.registerInstance(intentMonitor);

    handlerForMainLooper = new Handler(Looper.getMainLooper());
    final int corePoolSize = 0;
    final long keepAliveTime = 0L;
    executorService =
        new ThreadPoolExecutor(
            corePoolSize,
            Integer.MAX_VALUE,
            keepAliveTime,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable runnable) {
                Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                thread.setName(MonitoringInstrumentation.class.getSimpleName());
                return thread;
              }
            });
    Looper.myQueue().addIdleHandler(idleHandler);
    super.onCreate(arguments);
    specifyDexMakerCacheProperty();
    setupDexmakerClassloader();
    useDefaultInterceptingActivityFactory();
  }

  protected void installMultidex() {
    // Typically multidex is installed by inserting call at Application#attachBaseContext
    // However instrumentation#onCreate is called before Application#attachBaseContext. Thus
    // need to install it here, if its on classpath.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      try {
        Class<?> multidex = getMultiDexClass();
        try {
          Method installInstrumentation =
              multidex.getDeclaredMethod("installInstrumentation", Context.class, Context.class);
          installInstrumentation.invoke(null, getContext(), getTargetContext());
        } catch (NoSuchMethodException nsme) {
          Log.w(
              TAG,
              "Could not find MultiDex.installInstrumentation. Calling MultiDex.install instead."
                  + " Is an old version of the multidex library being used? If test app is using"
                  + " multidex, classes might not be found");
          installOldMultiDex(multidex);
        }
      } catch (ClassNotFoundException ignored) {
        Log.i(TAG, "No multidex.");
      } catch (NoSuchMethodException nsme) {
        Log.i(TAG, "No multidex.", nsme);
      } catch (InvocationTargetException ite) {
        throw new RuntimeException("multidex is available at runtime, but calling it failed.", ite);
      } catch (IllegalAccessException iae) {
        throw new RuntimeException("multidex is available at runtime, but calling it failed.", iae);
      }
    }
  }

  private static Class<?> getMultiDexClass() throws ClassNotFoundException {
    try {
      return Class.forName("androidx.multidex.MultiDex");
    } catch (ClassNotFoundException e) {
      // check for support multidex
      return Class.forName("androidx.multidex.MultiDex");
    }
  }

  /**
   * Perform application MultiDex installation only when instrumentation installation is not
   * available. Called when MultiDex class is available but MultiDex.installInstrumentation is not.
   */
  protected void installOldMultiDex(Class<?> multidexClass)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method install = multidexClass.getDeclaredMethod("install", Context.class);
    install.invoke(null, getTargetContext());
  }

  protected void specifyDexMakerCacheProperty() {
    // DexMaker uses heuristics to figure out where to store its temporary dex files
    // these heuristics may break (eg - they no longer work on JB MR2). So we create
    // our own cache dir to be used if the app doesnt specify a cache dir, rather then
    // relying on heuristics.
    //
    File dexCache = getTargetContext().getDir("dxmaker_cache", Context.MODE_PRIVATE);
    System.getProperties().put("dexmaker.dexcache", dexCache.getAbsolutePath());
  }

  protected final void setJsBridgeClassName(final String className) {
    if (null == className) {
      throw new NullPointerException("JsBridge class name cannot be null!");
    }

    if (isJsBridgeLoaded.get()) {
      throw new IllegalStateException("JsBridge is already loaded!");
    }
    jsBridgeClassName = className;
  }

  private void setupDexmakerClassloader() {

    if (Boolean.TRUE.equals(isDexmakerClassLoaderInitialized.get())) {
      // We've already setup dexmaker for this ContextClassLoader, so let's not mess with
      // the user.
      return;
    }

    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    // must set the context classloader for apps that use a shared uid, see
    // frameworks/base/core/java/android/app/LoadedApk.java
    ClassLoader newClassLoader = getTargetContext().getClassLoader();

    if (originalClassLoader != newClassLoader) {
      Log.i(
          TAG,
          String.format(
              "Setting context classloader to '%s', Original: '%s'",
              newClassLoader, originalClassLoader));
      Thread.currentThread().setContextClassLoader(newClassLoader);
    }
    isDexmakerClassLoaderInitialized.set(Boolean.TRUE);
  }

  private void logUncaughtExceptions() {
    standardHandler = Thread.currentThread().getUncaughtExceptionHandler();
    Thread.currentThread()
        .setUncaughtExceptionHandler(
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread t, Throwable e) {
                onException(t, e);
                if (null != standardHandler) {
                  Log.w(
                      TAG,
                      String.format(
                          "Invoking uncaught exception handler %s (a %s)",
                          standardHandler, standardHandler.getClass()));
                  standardHandler.uncaughtException(t, e);
                } else {
                  Log.w(TAG, "Invoking uncaught exception handler for thread: " + t.getName());
                  t.getThreadGroup().uncaughtException(t, e);
                }
                if (!"robolectric".equals(Build.FINGERPRINT)
                    && Looper.getMainLooper().getThread().equals(t)) {
                  // Running within the Android OS and the handler didn't kill the main thread
                  // Now we're in a state where the main looper is stopped and the Android OS
                  // can no longer commmunicate with the app - by crashing we ensure the
                  // am instrument command exits cleanly.
                  Log.e(TAG, "The main thread has died and the handlers didn't care, exiting");
                  System.exit(-10);
                }
              }
            });
  }

  protected void restoreUncaughtExceptionHandler() {
    Thread.currentThread().setUncaughtExceptionHandler(standardHandler);
  }

  /**
   * This implementation of onStart() will guarantee that the Application's onCreate method has
   * completed when it returns.
   *
   * <p>Subclasses should call super.onStart() before executing any code that touches the
   * application and it's state.
   */
  @Override
  public void onStart() {
    super.onStart();

    if (jsBridgeClassName != null) {
      tryLoadingJsBridge(jsBridgeClassName);
    }

    // Due to the way Android initializes instrumentation - all instrumentations have the
    // possibility of seeing the Application and its classes in an inconsistent state.
    // Specifically ActivityThread creates Instrumentation first, initializes it, and calls
    // instrumentation.onCreate(). After it does that, it calls
    // instrumentation.callApplicationOnCreate() which ends up calling the application's
    // onCreateMethod.
    //
    // So, Android's InstrumentationTestRunner's onCreate method() spawns a separate thread to
    // execute tests. This causes tests to start accessing the application and its classes while
    // the ActivityThread is calling callApplicationOnCreate() in its own thread.
    //
    // This makes it possible for tests to see the application in a state that is normally never
    // visible: pre-application.onCreate() and during application.onCreate()).
    //
    // *phew* that sucks! Here we waitForOnIdleSync() to ensure onCreate has completed before we
    // start executing tests.
    waitForIdleSync();

    // If the user has not yet set up a ContextClassLoader, they may need one set up for them
    // now.  They cannot see the one we set up in onCreate() because that took place on a
    // different thread.
    setupDexmakerClassloader();

    InstrumentationConnection.getInstance().init(this, new ActivityFinisher());
  }

  /**
   * Ensures all activities launched in this instrumentation are finished before the instrumentation
   * exits.
   *
   * <p>Subclasses who override this method should do their finish processing and then call
   * super.finish to invoke this logic. Not waiting for all activities to finish() before exiting
   * can cause device wide instability.
   */
  @Override
  public void finish(int resultCode, Bundle results) {
    if (finished) {
      Log.w(TAG, "finish called 2x!");
      return;
    } else {
      finished = true;
    }

    if (shouldWaitForActivitiesToComplete()) {
      handlerForMainLooper.post(new ActivityFinisher());
      long startTime = System.currentTimeMillis();
      waitForActivitiesToComplete();
      long endTime = System.currentTimeMillis();
      Log.i(TAG, String.format("waitForActivitiesToComplete() took: %sms", endTime - startTime));
    }
    ActivityLifecycleMonitorRegistry.registerInstance(null);
    restoreUncaughtExceptionHandler();
    super.finish(resultCode, results);
  }

  protected boolean shouldWaitForActivitiesToComplete() {
    // TODO(b/72831103): default this argument to false in a future release
    return Boolean.parseBoolean(
        InstrumentationRegistry.getArguments().getString("waitForActivitiesToComplete", "true"));
  }

  /**
   * Ensures we've onStopped() all activities which were onStarted().
   *
   * <p>According to Activity's contract, the process is not killable between onStart and onStop.
   * Breaking this contract (which finish() will if you let it) can cause bad behaviour (including a
   * full restart of system_server).
   *
   * <p>We give the app 2 seconds to stop all its activities, then we proceed.
   *
   * <p>This should never be run on the main thread.
   */
  protected void waitForActivitiesToComplete() {
    if (Looper.getMainLooper() == Looper.myLooper()) {
      throw new IllegalStateException("Cannot be called from main thread!");
    }

    long endTime = System.currentTimeMillis() + MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP;
    int currentActivityCount = startedActivityCounter.get();
    while (currentActivityCount > 0 && System.currentTimeMillis() < endTime) {
      try {
        Log.i(TAG, "Unstopped activity count: " + currentActivityCount);
        Thread.sleep(MILLIS_TO_POLL_FOR_ACTIVITY_STOP);
        currentActivityCount = startedActivityCounter.get();
      } catch (InterruptedException ie) {
        Log.i(TAG, "Abandoning activity wait due to interruption.", ie);
        break;
      }
    }

    if (currentActivityCount > 0) {
      dumpThreadStateToOutputs("ThreadState-unstopped.txt");
      Log.w(
          TAG,
          String.format(
              "Still %s activities active after waiting %s ms.",
              currentActivityCount, MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP));
    }
  }

  @Override
  public void onDestroy() {
    Log.i(TAG, "Instrumentation Finished!");
    Looper.myQueue().removeIdleHandler(idleHandler);

    InstrumentationConnection.getInstance().terminate();

    super.onDestroy();
  }

  @Override
  public void callApplicationOnCreate(Application app) {
    applicationMonitor.signalLifecycleChange(app, ApplicationStage.PRE_ON_CREATE);
    super.callApplicationOnCreate(app);
    applicationMonitor.signalLifecycleChange(app, ApplicationStage.CREATED);
  }

  /**
   * Posts a runnable to the main thread and blocks the caller's thread until the runnable is
   * executed. When a Throwable is thrown in the runnable, the exception is propagated back to the
   * caller's thread. If it is an unchecked throwable, it will be rethrown as is. If it is a checked
   * exception, it will be rethrown as a {@link RuntimeException}.
   *
   * @param runnable a runnable to be executed on the main thread
   */
  @Override
  public void runOnMainSync(Runnable runnable) {
    FutureTask<Void> wrapped = new FutureTask<>(runnable, null);
    super.runOnMainSync(wrapped);
    try {
      wrapped.get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new RuntimeException(cause);
    }
  }

  @Override
  public Activity startActivitySync(final Intent intent) {
    checkNotMainThread();
    long lastIdleTimeBeforeLaunch = lastIdleTime.get();

    if (anActivityHasBeenLaunched.compareAndSet(false, true)) {
      // All activities launched from InstrumentationTestCase.launchActivityWithIntent get
      // started with FLAG_ACTIVITY_NEW_TASK. This includes calls to
      // ActivityInstrumentationTestcase2.getActivity().
      //
      // This gives us a pristine environment - MOST OF THE TIME.
      //
      // However IF we've run a test method previously and that has launched an activity
      // outside of our process our old task is still lingering around. By launching a new
      // activity android will place our activity at the bottom of the stack and bring the
      // previous external activity to the front of the screen.
      //
      // To wipe out the old task and execute within a pristine environment for each test
      // we tell android to CLEAR_TOP the very first activity we see, no matter what.
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    Future<Activity> startedActivity =
        executorService.submit(
            new Callable<Activity>() {
              @Override
              public Activity call() {
                return MonitoringInstrumentation.super.startActivitySync(intent);
              }
            });

    try {
      return startedActivity.get(ActivityLifecycleTimeout.getMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      dumpThreadStateToOutputs("ThreadState-startActivityTimeout.txt");
      startedActivity.cancel(true);
      throw new RuntimeException(
          String.format(
              "Could not launch intent %s within %s milliseconds."
                  + " Perhaps the main thread has not gone idle within a reasonable amount of "
                  + "time? There could be an animation or something constantly repainting the "
                  + "screen. Or the activity is doing network calls on creation? See the "
                  + "threaddump logs. For your reference the last time the event queue was idle "
                  + "before your activity launch request was %s and now the last time the queue "
                  + "went idle was: %s. If these numbers are the same your activity might be "
                  + "hogging the event queue.",
              intent,
              ActivityLifecycleTimeout.getMillis(),
              lastIdleTimeBeforeLaunch,
              lastIdleTime.get()));
    } catch (ExecutionException ee) {
      throw new RuntimeException("Could not launch activity", ee.getCause());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted", ie);
    }
  }

  /** {@inheritDoc} */
  @Override
  public ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      Activity target,
      Intent intent,
      int requestCode) {
    intentMonitor.signalIntent(intent);
    ActivityResult ar = stubResultFor(intent);
    if (ar != null) {
      Log.i(TAG, String.format("Stubbing intent %s", intent));
      return ar;
    }
    return super.execStartActivity(who, contextThread, token, target, intent, requestCode);
  }

  /** {@inheritDoc} */
  @Override
  public ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      Activity target,
      Intent intent,
      int requestCode,
      Bundle options) {
    intentMonitor.signalIntent(intent);
    ActivityResult ar = stubResultFor(intent);
    if (ar != null) {
      Log.i(TAG, String.format("Stubbing intent %s", intent));
      return ar;
    }
    return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
  }

  /** This API was added in Android API 23 (M) */
  @Override
  public ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      String target,
      Intent intent,
      int requestCode,
      Bundle options) {
    intentMonitor.signalIntent(intent);
    ActivityResult ar = stubResultFor(intent);
    if (ar != null) {
      Log.i(TAG, String.format("Stubbing intent %s", intent));
      return ar;
    }
    return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
  }

  /** This API was added in Android API 17 (JELLY_BEAN_MR1) */
  @Override
  public ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      Activity target,
      Intent intent,
      int requestCode,
      Bundle options,
      UserHandle user) {
    return super.execStartActivity(
        who, contextThread, token, target, intent, requestCode, options, user);
  }

  /** {@inheritDoc} */
  @Override
  public void execStartActivities(
      Context who,
      IBinder contextThread,
      IBinder token,
      Activity target,
      Intent[] intents,
      Bundle options) {
    // This method is used in HONEYCOMB and higher to create a synthetic back stack for the
    // launched activity. The intent at the end of the array is the top most, user visible
    // activity, and the intents beneath it are launched when the user presses back.
    Log.d(TAG, "execStartActivities(context, ibinder, ibinder, activity, intent[], bundle)");
    // For requestCode < 0, the caller doesn't expect any result and
    // in this case we are not expecting any result so selecting
    // a value < 0.
    int requestCode = -1;
    for (Intent intent : intents) {
      execStartActivity(who, contextThread, token, target, intent, requestCode, options);
    }
  }

  /** {@inheritDoc} */
  @Override
  public ActivityResult execStartActivity(
      Context who,
      IBinder contextThread,
      IBinder token,
      Fragment target,
      Intent intent,
      int requestCode,
      Bundle options) {
    Log.d(TAG, "execStartActivity(context, IBinder, IBinder, Fragment, Intent, int, Bundle)");
    intentMonitor.signalIntent(intent);
    ActivityResult ar = stubResultFor(intent);
    if (ar != null) {
      Log.i(TAG, String.format("Stubbing intent %s", intent));
      return ar;
    }
    return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
  }

  private static class StubResultCallable implements Callable<ActivityResult> {
    private final Intent intent;

    StubResultCallable(Intent intent) {
      this.intent = intent;
    }

    @Override
    public ActivityResult call() {
      return IntentStubberRegistry.getInstance().getActivityResultForIntent(intent);
    }
  }

  private ActivityResult stubResultFor(Intent intent) {
    if (IntentStubberRegistry.isLoaded()) {
      // Activities can be launched from the instrumentation thread, so if that's the case,
      // get on main thread to retrieve the result.
      if (Looper.myLooper() != Looper.getMainLooper()) {
        FutureTask<ActivityResult> task =
            new FutureTask<ActivityResult>(new StubResultCallable(intent));
        runOnMainSync(task);
        try {
          return task.get();
        } catch (ExecutionException e) {
          throw new RuntimeException(
              String.format("Could not retrieve stub result for intent %s", intent), e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      } else {
        return IntentStubberRegistry.getInstance().getActivityResultForIntent(intent);
      }
    }
    return null;
  }

  @Override
  public boolean onException(Object obj, Throwable e) {
    String error =
        String.format(
            "Exception encountered by: %s. Dumping thread state to "
                + "outputs and pining for the fjords.",
            obj);
    Log.e(TAG, error, e);
    dumpThreadStateToOutputs("ThreadState-onException.txt");
    Log.e(TAG, "Dying now...");
    return super.onException(obj, e);
  }

  protected void dumpThreadStateToOutputs(String outputFileName) {
    String threadState = getThreadState();
    Log.e("THREAD_STATE", threadState);
  }

  protected String getThreadState() {
    Set<Map.Entry<Thread, StackTraceElement[]>> threads = Thread.getAllStackTraces().entrySet();
    StringBuilder threadState = new StringBuilder();
    for (Map.Entry<Thread, StackTraceElement[]> threadAndStack : threads) {
      StringBuilder threadMessage = new StringBuilder("  ").append(threadAndStack.getKey());
      threadMessage.append("\n");
      for (StackTraceElement ste : threadAndStack.getValue()) {
        threadMessage.append("    ");
        threadMessage.append(ste.toString());
        threadMessage.append("\n");
      }
      threadMessage.append("\n");
      threadState.append(threadMessage.toString());
    }
    return threadState.toString();
  }

  @Override
  public void callActivityOnDestroy(Activity activity) {
    super.callActivityOnDestroy(activity);
    lifecycleMonitor.signalLifecycleChange(Stage.DESTROYED, activity);
  }

  @Override
  public void callActivityOnRestart(Activity activity) {
    super.callActivityOnRestart(activity);
    lifecycleMonitor.signalLifecycleChange(Stage.RESTARTED, activity);
  }

  @Override
  public void callActivityOnCreate(Activity activity, Bundle bundle) {
    lifecycleMonitor.signalLifecycleChange(Stage.PRE_ON_CREATE, activity);
    super.callActivityOnCreate(activity, bundle);
    lifecycleMonitor.signalLifecycleChange(Stage.CREATED, activity);
  }

  // NOTE: we need to keep a count of activities between the start
  // and stop lifecycle internal to our instrumentation. Exiting the test
  // process with activities in this state can cause crashes/flakiness
  // that would impact a subsequent test run.
  @Override
  public void callActivityOnStart(Activity activity) {
    startedActivityCounter.incrementAndGet();
    try {
      super.callActivityOnStart(activity);
      lifecycleMonitor.signalLifecycleChange(Stage.STARTED, activity);
    } catch (RuntimeException re) {
      startedActivityCounter.decrementAndGet();
      throw re;
    }
  }

  @Override
  public void callActivityOnStop(Activity activity) {
    try {
      super.callActivityOnStop(activity);
      lifecycleMonitor.signalLifecycleChange(Stage.STOPPED, activity);
    } finally {
      startedActivityCounter.decrementAndGet();
    }
  }

  @Override
  public void callActivityOnResume(Activity activity) {
    super.callActivityOnResume(activity);
    lifecycleMonitor.signalLifecycleChange(Stage.RESUMED, activity);
  }

  @Override
  public void callActivityOnPause(Activity activity) {
    super.callActivityOnPause(activity);
    lifecycleMonitor.signalLifecycleChange(Stage.PAUSED, activity);
  }

  // ActivityUnitTestCase defaults to building the ComponentName via
  // Activity.getClass().getPackage().getName(). This will cause a problem if the Java Package of
  // the Activity is not the Android Package of the application, specifically
  // Activity.getPackageName() will return an incorrect value.
  // @see b/14561718
  @Override
  public Activity newActivity(
      Class<?> clazz,
      Context context,
      IBinder token,
      Application application,
      Intent intent,
      ActivityInfo info,
      CharSequence title,
      Activity parent,
      String id,
      Object lastNonConfigurationInstance)
      throws InstantiationException, IllegalAccessException {
    String activityClassPackageName = clazz.getPackage().getName();
    String contextPackageName = context.getPackageName();
    ComponentName intentComponentName = intent.getComponent();
    if (!contextPackageName.equals(intentComponentName.getPackageName())) {
      if (activityClassPackageName.equals(intentComponentName.getPackageName())) {
        intent.setComponent(
            new ComponentName(contextPackageName, intentComponentName.getClassName()));
      }
    }
    return super.newActivity(
        clazz,
        context,
        token,
        application,
        intent,
        info,
        title,
        parent,
        id,
        lastNonConfigurationInstance);
  }

  @Override
  public Activity newActivity(ClassLoader cl, String className, Intent intent)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    return interceptingActivityFactory.shouldIntercept(cl, className, intent)
        ? interceptingActivityFactory.create(cl, className, intent)
        : super.newActivity(cl, className, intent);
  }

  /**
   * Use the given InterceptingActivityFactory to create Activity instance in {@link
   * #newActivity(ClassLoader, String, Intent)}. This can be used to override default behavior of
   * activity in tests e.g. mocking startService() method in Activity under test, to avoid starting
   * the real service and instead verifying that a particular service was started.
   *
   * @param interceptingActivityFactory InterceptingActivityFactory to be used for creating activity
   *     instance in {@link #newActivity(ClassLoader, String, Intent)}
   */
  public void interceptActivityUsing(InterceptingActivityFactory interceptingActivityFactory) {
    Checks.checkNotNull(interceptingActivityFactory);
    this.interceptingActivityFactory = interceptingActivityFactory;
  }

  /**
   * Use default mechanism of creating activity instance in {@link #newActivity(ClassLoader, String,
   * Intent)}
   */
  public void useDefaultInterceptingActivityFactory() {
    interceptingActivityFactory = new DefaultInterceptingActivityFactory();
  }

  /**
   * Loads the JS Bridge for Espresso Web. This method will be ran on the main thread!
   *
   * @param className the name of the JsBridge class
   */
  private void tryLoadingJsBridge(final String className) {
    if (null == className) {
      throw new NullPointerException("JsBridge class name cannot be null!");
    }
    runOnMainSync(
        new Runnable() {
          @Override
          public void run() {
            try {
              Class<?> jsBridge = Class.forName(className);
              Method install = jsBridge.getDeclaredMethod("installBridge");
              install.invoke(null);
              isJsBridgeLoaded.set(true);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
              Log.i(TAG, "No JSBridge.");
            } catch (InvocationTargetException | IllegalAccessException ite) {
              throw new RuntimeException(
                  "JSbridge is available at runtime, but calling it failed.", ite);
            }
          }
        });
  }

  /**
   * Loops through all the activities that have not yet finished and explicitly calls finish on
   * them.
   */
  public class ActivityFinisher implements Runnable {
    @Override
    public void run() {
      List<Activity> activities = new ArrayList<>();

      for (Stage s : EnumSet.range(Stage.CREATED, Stage.STOPPED)) {
        activities.addAll(lifecycleMonitor.getActivitiesInStage(s));
      }

      if (activities.size() > 0) {
        Log.i(TAG, "Activities that are still in CREATED to STOPPED: " + activities.size());
      }

      for (Activity activity : activities) {
        if (!activity.isFinishing()) {
          try {
            Log.i(TAG, "Finishing activity: " + activity);
            activity.finish();
          } catch (RuntimeException e) {
            Log.e(TAG, "Failed to finish activity.", e);
          }
        }
      }
    }
  }

  /**
   * Checks whether this instance of instrumentation should be considered as a primary
   * instrumentation process.
   *
   * <p>Prior to API 26, instrumentation could only run in a single process and that would be the
   * primary process. Post API 26, the primary process is the first process listed in
   * android:targetProcesses or the default process of the targetPackage.
   *
   * @deprecated use isPrimaryInstrProcess()
   * @param argsProcessName unused.
   * @return {@code true} if the given process is the primary instrumentation process
   */
  @Deprecated
  protected boolean isPrimaryInstrProcess(@Nullable String argsProcessName) {
    return isPrimaryInstrProcess();
  }

  /**
   * Checks whether this instance of instrumentation should be considered as a primary
   * instrumentation process.
   *
   * <p>Prior to API 26, instrumentation could only run in a single process and that would be the
   * primary process. Post API 26, the primary process is the first process listed in
   * android:targetProcesses or the default process of the targetPackage.
   *
   * @return {@code true} if the given process is the primary instrumentation process
   */
  protected final boolean isPrimaryInstrProcess() {
    return isOriginalInstrumentationProcess();
  }

  /** Determines if a given ProcSummary is hosting a particular android:process. */
  private boolean isHostingProcess(String wantName, ProcSummary ps) {
    int wantLen = wantName.length();
    int cmdLen = ps.cmdline.length();
    if (wantLen == cmdLen) {
      // If wantName == "a.b.c" and process name is "a.b.c" we still might be truncated at exactly
      // right point (maybe process full name is "a.b.c:d"), so check suffix too.
      return wantName.equals(ps.cmdline) && wantName.endsWith(ps.name);
    } else if (wantLen < cmdLen) {
      // obviously not the same.
      return false;
    } else {

      // ProcSummary.cmdline length is limited to the size of the original zygote cmdline
      // (See Process.setARGV0 / app_main.cpp computeArgBlockSize)
      //
      // cmdline is truncated to this length if process name is longer.
      // Interestingly ps.name (which is /proc/pid/comm / pthread_getname / PR_GET_NAME)
      // is the last 15 chars (See com_android_internal_os_Zygote / SetThreadName /
      // MAX_TASK_COMM_LEN in bionic.
      //
      // So if wantName.substring(0, ps.cmdline.length()).equals(ps.cmdline) &&
      // wantName.substring(wantName.length() - ps.name.length()).equals(ps.name) then
      // most likely - they are the same.
      if (wantName.startsWith(ps.cmdline)) {
        if (wantName.endsWith(ps.name)) {
          Log.w(
              TAG,
              "Use smaller processNames in AndroidManifest.xml. Long names are truncated. "
                  + "This process's cmdline is a prefix of the processName and suffix of "
                  + "comm - assuming: "
                  + ps
                  + " is: "
                  + wantName);
          return true;
        }
      }
      return false;
    }
  }

  private boolean isOriginalInstrumentationProcess() {

    Boolean isOriginal = isOriginalInstr;
    if (isOriginal == null) {
      isOriginal = isOriginalUncached();
      isOriginalInstr = isOriginal;
    }
    return isOriginal;
  }

  private List<String> getTargetProcessValues() {
    if (Build.VERSION.SDK_INT < 26) {
      return Collections.emptyList();
    }
    String tpVal = null;
    try {
      tpVal =
          getContext()
              .getPackageManager()
              .getInstrumentationInfo(getComponentName(), 0)
              .targetProcesses;
      if (tpVal == null) {
        tpVal = "";
      }
      tpVal = tpVal.trim();
    } catch (PackageManager.NameNotFoundException unpossible) {
      Log.wtf(TAG, "Cannot locate ourselves: " + getComponentName(), unpossible);
      throw new IllegalStateException("Cannot locate ourselves: " + getComponentName(), unpossible);
    }
    if (tpVal.length() == 0) {
      return Collections.emptyList();
    }

    List<String> tps = new ArrayList<>();
    for (String tp : tpVal.split(",", -1)) {
      tp = tp.trim();
      if (tp.length() > 0) {
        tps.add(tp);
      }
    }
    return tps;
  }

  private boolean isOriginalUncached() {
    if (Build.VERSION.SDK_INT < 26) {
      return true;
    }
    List<String> targetProcesses = getTargetProcessValues();
    if (targetProcesses.isEmpty()) {
      // if android:targetProcesses is unspecified, android will only launch instr in 1 process.
      return true;
    }
    boolean isWildcard = "*".equals(targetProcesses.get(0));
    if (targetProcesses.size() == 1 && !isWildcard) {
      // if android:targetProcesses is specified and not wildcarded, the system will only launch
      // instr in 1 process.
      return true;
    }

    ProcSummary me = null;

    try {
      me = ProcSummary.summarize("self");
    } catch (ProcSummary.SummaryException se) {
      Log.w(TAG, "Could not list apps for this user, running in sandbox? Assuming primary", se);
      return false;
    }

    if (isWildcard) {
      // in wildcard mode treat the default app process as the primary process.
      String appDefProcessName = getTargetContext().getApplicationInfo().processName;
      if (appDefProcessName == null) {
        appDefProcessName = getTargetContext().getPackageName();
      }
      return isHostingProcess(appDefProcessName, me);
    } else {
      // in targeted mode treat the 1st name listed in android:targetProcesses as primary.
      return isHostingProcess(targetProcesses.get(0), me);
    }
  }
}
