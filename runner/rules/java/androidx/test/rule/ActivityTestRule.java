/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.rule;

import static androidx.test.internal.util.Checks.checkNotNull;
import static androidx.test.internal.util.Checks.checkState;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import androidx.test.annotation.UiThreadTest;
import androidx.test.internal.runner.junit4.statement.UiThreadStatement;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.MonitoringInstrumentation;
import androidx.test.runner.intercepting.SingleActivityFactory;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * This rule provides functional testing of a single {@link Activity}. When {@code launchActivity}
 * is set to true in the constructor, the Activity under test will be launched before each test
 * annotated with <a href="http://junit.org/javadoc/latest/org/junit/Test.html"><code>Test</code>
 * </a> and before methods annotated with <a
 * href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>, and it
 * will be terminated after the test is completed and methods annotated with <a
 * href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After
 * </code></a> are finished.
 *
 * <p>The Activity can be manually launched with {@link #launchActivity(Intent)}, and manually
 * finished with {@link #finishActivity()}. If the Activity is running at the end of the test, the
 * test rule will finish it.
 *
 * <p>During the duration of the test you will be able to manipulate your Activity directly using
 * the reference obtained from {@link #getActivity()}. If the Activity is finished and relaunched,
 * the reference returned by {@link #getActivity()} will always point to the current instance of the
 * Activity.
 *
 * @param <T> The Activity class under test
 * @deprecated use {@link androidx.test.core.app.ActivityScenario} or {@link
 *     androidx.test.ext.junit.rules.ActivityScenarioRule} instead. They offer a simpler, and safer
 *     way of controlling Activity lifecycles.
 *     <p>Here are some tips to consider when converting to <code>ActivityScenario/Rule</code>:
 *     <ol>
 *       <li>For simple cases where you want to launch the Activity before each test and tear it
 *           down after each test (eg you are using {@link #ActivityTestRule(Class)}), convert
 *           directly to ActivityScenarioRule.
 *       <li>If you need control over when to launch the Activity (eg you are using {@link
 *           #ActivityTestRule(Class, false, false)}, use ActivityScenario.launch. Its recommended
 *           to wrap the launch in a try-block, so the Activity is closed automatically. <code>
 *       try (ActivityScenario.launch(activityClass)) {
 *         ...
 *       }
 *     </code>
 *       <li>If you need access to the Activity during the test (eg you are calling {@link
 *           ActivityTestRule#getActivity()} provide a Runnable callback to {@link
 *           androidx.test.core.app.ActivityScenario#onActivity(Runnable)} instead. The callback
 *           provided to onActivity will run on the application's main thread, thus ensuring a safer
 *           mechanism to access the Activity.
 *     </ol>
 */
@Deprecated
public class ActivityTestRule<T extends Activity> implements TestRule {

  private static final String TAG = "ActivityTestRule";

  private static final int NO_FLAGS_SET = 0;
  private static final String FIELD_RESULT_CODE = "mResultCode";
  private static final String FIELD_RESULT_DATA = "mResultData";

  private final Class<T> activityClass;

  private final String targetPackage;

  private final int launchFlags;

  private final ActivityLifecycleCallback lifecycleCallback = new LifecycleCallback();

  private Instrumentation instrumentation;

  private boolean initialTouchMode = false;

  private boolean launchActivity = false;

  private SingleActivityFactory<T> activityFactory;

  @VisibleForTesting volatile WeakReference<T> activity = makeWeakReference(null);

  private volatile ActivityResult activityResult;

  /**
   * Similar to {@link #ActivityTestRule(Class, boolean)} but with "touch mode" disabled.
   *
   * @param activityClass The activity under test. This must be a class in the instrumentation
   *     targetPackage specified in the AndroidManifest.xml
   * @see ActivityTestRule#ActivityTestRule(Class, boolean, boolean)
   */
  public ActivityTestRule(Class<T> activityClass) {
    this(activityClass, false);
  }

  /**
   * Similar to {@link #ActivityTestRule(Class, boolean, boolean)} but defaults to launch the
   * activity under test once per <a href="http://junit.org/javadoc/latest/org/junit/Test.html">
   * <code>Test</code></a> method. It is launched before the first <a
   * href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>
   * method, and terminated after the last <a
   * href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After</code></a> method.
   *
   * @param activityClass The activity under test. This must be a class in the instrumentation
   *     targetPackage specified in the AndroidManifest.xml
   * @param initialTouchMode true if the Activity should be placed into "touch mode" when started
   * @see ActivityTestRule#ActivityTestRule(Class, boolean, boolean)
   */
  public ActivityTestRule(Class<T> activityClass, boolean initialTouchMode) {
    this(activityClass, initialTouchMode, true);
  }

  /**
   * Similar to {@link #ActivityTestRule(Class, String, int, boolean, boolean)} but defaults to
   * launch the Activity with the default target package name {@link
   * InstrumentationRegistry#getTargetContext()#getPackageName} and {@link
   * Intent#FLAG_ACTIVITY_NEW_TASK} launch flag.
   *
   * @param activityClass The activity under test. This must be a class in the instrumentation
   *     targetPackage specified in the AndroidManifest.xml
   * @param initialTouchMode true if the Activity should be placed into "touch mode" when started
   * @param launchActivity true if the Activity should be launched once per <a
   *     href="http://junit.org/javadoc/latest/org/junit/Test.html"><code>Test</code></a> method. It
   *     will be launched before the first <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>
   *     method, and terminated after the last <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After</code></a>
   *     method.
   */
  public ActivityTestRule(
      Class<T> activityClass, boolean initialTouchMode, boolean launchActivity) {
    this(
        activityClass,
        InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
        Intent.FLAG_ACTIVITY_NEW_TASK,
        initialTouchMode,
        launchActivity);
  }

  /**
   * Creates an {@link ActivityTestRule} for the Activity under test.
   *
   * @param activityFactory factory to be used for creating Activity instance
   * @param initialTouchMode true if the Activity should be placed into "touch mode" when started
   * @param launchActivity true if the Activity should be launched once per <a
   *     href="http://junit.org/javadoc/latest/org/junit/Test.html"><code>Test</code></a> method. It
   *     will be launched before the first <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>
   *     method, and terminated after the last <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After</code></a>
   *     method.
   */
  public ActivityTestRule(
      SingleActivityFactory<T> activityFactory, boolean initialTouchMode, boolean launchActivity) {
    this(activityFactory.getActivityClassToIntercept(), initialTouchMode, launchActivity);
    this.activityFactory = activityFactory;
  }

  /**
   * Creates an {@link ActivityTestRule} for the Activity under test.
   *
   * @param activityClass The activity under test. This must be a class in the instrumentation
   *     targetPackage specified in the AndroidManifest.xml
   * @param initialTouchMode true if the Activity should be placed into "touch mode" when started
   * @param launchActivity true if the Activity should be launched once per <a
   *     href="http://junit.org/javadoc/latest/org/junit/Test.html"><code>Test</code></a> method. It
   *     will be launched before the first <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>
   *     method, and terminated after the last <a
   *     href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After</code></a>
   *     method.
   * @param targetPackage The name of the target package that the Activity is started under. This
   *     value is passed down to the start Intent using {@link
   *     Intent#setClassName(android.content.Context, String)}. Can not be null.
   * @param launchFlags launch flags to start the Activity under test.
   */
  public ActivityTestRule(
      Class<T> activityClass,
      @NonNull String targetPackage,
      int launchFlags,
      boolean initialTouchMode,
      boolean launchActivity) {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    this.activityClass = activityClass;
    this.targetPackage = checkNotNull(targetPackage, "targetPackage cannot be null!");
    this.launchFlags = launchFlags;
    this.initialTouchMode = initialTouchMode;
    this.launchActivity = launchActivity;
  }

  /**
   * Override this method to set up a custom Intent as if supplied to {@link
   * android.content.Context#startActivity}. Custom Intents provided by this method will take
   * precedence over default Intents that where created in the constructor but be overridden by any
   * Intents passed in through {@link #launchActivity(Intent)}.
   *
   * <p>The default Intent (if this method returns null or is not overwritten) is: action = {@link
   * Intent#ACTION_MAIN} flags = {@link Intent#FLAG_ACTIVITY_NEW_TASK} All other intent fields are
   * null or empty.
   *
   * <p>If the custom Intent provided by this methods overrides any of the following fields:
   *
   * <ul>
   *   <li>componentName through {@link Intent#setClassName(String, String)}
   *   <li>launch flags through {@link Intent#setFlags(int)}
   * </ul>
   *
   * <p>These custom values will be used to start the Activity. However, if some of these values are
   * not set, the default values documented in {@link #ActivityTestRule(Class, String, int, boolean,
   * boolean)} are supplemented.
   *
   * @return The Intent as if supplied to {@link android.content.Context#startActivity}.
   */
  protected Intent getActivityIntent() {
    return null;
  }

  /**
   * Override this method to execute any code that should run before your {@link Activity} is
   * created and launched. This method is called before each test method, including any method
   * annotated with <a href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>
   * Before</code></a>.
   */
  protected void beforeActivityLaunched() {
    // empty by default
  }

  /**
   * Override this method to execute any code that should run after your {@link Activity} is
   * launched, but before any test code is run including any method annotated with <a
   * href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before</code></a>.
   *
   * <p>Prefer <a href="http://junit.sourceforge.net/javadoc/org/junit/Before.html"><code>Before
   * </code></a> over this method. This method should usually not be overwritten directly in tests
   * and only be used by subclasses of ActivityTestRule to get notified when the activity is created
   * and visible but test runs.
   */
  protected void afterActivityLaunched() {
    // empty by default
  }

  /**
   * Override this method to execute any code that should run after the currently launched {@link
   * Activity} is finished. This method is called after each test method, including any method
   * annotated with <a href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>After
   * </code></a>.
   *
   * <p>Prefer <a href="http://junit.sourceforge.net/javadoc/org/junit/After.html"><code>Before
   * </code></a> over this method. This method should usually not be overwritten directly in tests
   * and only be used by subclasses of ActivityTestRule to get notified when the activity is created
   * and visible but test runs.
   */
  protected void afterActivityFinished() {
    // empty by default
  }

  /**
   * Returns the reference to the activity under test.
   *
   * <p>The reference to the activity is assigned during the initial creation of the acivity and for
   * every sinlge {@link Activity#OnResumed()} lifecycle change.
   *
   * <p><b>Note:</b> Lifecycle changes happen on the UI thread (not the instrumentation thread where
   * this test code usually executes). Thus, the return value may vary depending on timing.
   *
   * <p>For example, if the activity is finished and relaunched, the reference returned by this
   * method will point to the new instance of the activity assuming {@link Activity#OnResumed()} was
   * called prior to calling this method.
   *
   * <p>If the activity wasn't created yet or already finished, {@code null} will be returned.
   *
   * <p><b>Note:</b> The activity reference is stored in a weak reference which means if the
   * activity under test is detroyed (ex. back button was pressed) then the system no longer holds a
   * strong reference to the acitivty and this refernce may get garbage collected.
   */
  public T getActivity() {
    T hardActivityRef = activity.get();
    if (hardActivityRef == null) {
      Log.w(TAG, "Activity wasn't created yet or already stopped");
    }
    return hardActivityRef;
  }

  @Override
  public Statement apply(final Statement base, Description description) {
    return new ActivityStatement(base);
  }

  /**
   * Launches the Activity under test.
   *
   * <p>Don't call this method directly, unless you explicitly requested not to lazily launch the
   * Activity manually using the launchActivity flag in {@link #ActivityTestRule(Class, boolean,
   * boolean)}.
   *
   * <p>Usage:
   *
   * <pre>
   *    &#064;Test
   *    public void customIntentToStartActivity() {
   *        Intent intent = new Intent(Intent.ACTION_PICK);
   *        activity = mActivityRule.launchActivity(intent);
   *    }
   * </pre>
   *
   * Note: Custom start Intents provided through this method will take precedence over default
   * Intents that where created in the constructor and any Intent returned from {@link
   * #getActivityIntent()}. The same override rules documented in {@link #getActivityIntent()}
   * apply.
   *
   * @param startIntent The Intent that will be used to start the Activity under test. If {@code
   *     startIntent} is null, the Intent returned by {@link ActivityTestRule#getActivityIntent()}
   *     is used.
   * @return the Activity launched by this rule.
   */
  public T launchActivity(@Nullable Intent startIntent) {
    Log.e("quintink", "************* ActivityTestRule: start launchActivity");
    // set initial touch mode
    instrumentation.setInTouchMode(initialTouchMode);

    // inject custom intent, if provided
    if (null == startIntent) {
      startIntent = getActivityIntent();
      if (null == startIntent) {
        Log.w(
            TAG,
            "getActivityIntent() returned null using default: " + "Intent(Intent.ACTION_MAIN)");
        startIntent = new Intent(Intent.ACTION_MAIN);
      }
    }

    // Set target component if not set Intent
    if (null == startIntent.getComponent()) {
      startIntent.setClassName(targetPackage, activityClass.getName());
    }

    // Set launch flags where if not set Intent
    if (NO_FLAGS_SET == startIntent.getFlags()) {
      startIntent.addFlags(launchFlags);
    }

    beforeActivityLaunched();
    // The following cast is correct because the activity we're creating is of the same type as
    // the one passed in
    T hardActivityRef = activityClass.cast(instrumentation.startActivitySync(startIntent));
    activity = makeWeakReference(hardActivityRef);

    instrumentation.waitForIdleSync();

    if (hardActivityRef != null) {
      // Notify that Activity was successfully launched
      ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycleCallback);
      afterActivityLaunched();
    } else {
      // Log an error message to logcat/instrumentation, that the Activity failed to launch
      String errorMessage =
          String.format("Activity %s, failed to launch", startIntent.getComponent());
      Bundle bundle = new Bundle();
      bundle.putString(Instrumentation.REPORT_KEY_STREAMRESULT, TAG + " " + errorMessage);
      instrumentation.sendStatus(0, bundle);
      Log.e(TAG, errorMessage);
    }

    Log.e("quintink", "************* ActivityTestRule: end launchActivity");
    return hardActivityRef;
  }

  @VisibleForTesting
  void setInstrumentation(Instrumentation instrumentation) {
    this.instrumentation = checkNotNull(instrumentation, "instrumentation cannot be null!");
  }

  /**
   * Finishes the currently launched Activity.
   *
   * @throws IllegalStateException if the Activity is not running or failed to finish it.
   */
  public void finishActivity() {
    try {
      if (activity.get() != null) {
        callFinishOnMainSync();
      }
    } finally {
      activity = makeWeakReference(null);
      afterActivityFinished(); // TODO(b/72327935) move down to evaluate
    }
  }

  @VisibleForTesting
  void callFinishOnMainSync() {
    try {
      final T hardActivityRef = activity.get();
      runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              checkState(
                  hardActivityRef != null,
                  "Activity was not launched. If you manually finished it, you must launch it"
                      + " again before finishing it. ");
              hardActivityRef.finish();
              // If there is an activity result we save it
              setActivityResultForActivity(hardActivityRef);
            }
          });
      instrumentation.waitForIdleSync();
    } catch (Throwable throwable) {
      // Should never happen
      String msg = "Failed to execute activity.finish() on the main thread";
      Log.e(TAG, msg, throwable);
      throw new IllegalStateException(msg, throwable);
    }
  }

  /**
   * This method can be used to retrieve the {@link ActivityResult} of an Activity that has called
   * {@link Activity#setResult}. Usually, the result is handled in {@link Activity#onActivityResult}
   * of the parent Activity, that has called {@link Activity#startActivityForResult}.
   *
   * <p>This method must <b>not</b> be called before {@code Activity.finish} was called or after the
   * activity was already destroyed.
   *
   * <p>Note: This method assumes {@link Activity#setResult(int)} is called no later than in {@link
   * Activity#onPause()}.
   *
   * @return the {@link ActivityResult} that was set most recently
   * @throws IllegalStateException if the activity is not in finishing state.
   */
  public ActivityResult getActivityResult() {
    if (null == activityResult) {
      // This is required if users manually called .finish() on their activity instead of using
      // this.finishActivity(). Since .finish() is async there could be a case that our callback
      // wasn't called just yet.
      T hardActivityRef = activity.get();
      checkNotNull(hardActivityRef, "Activity wasn't created yet or already destroyed!");
      try {
        runOnUiThread(
            new Runnable() {
              @Override
              public void run() {
                checkState(hardActivityRef.isFinishing(), "Activity is not finishing!");
                setActivityResultForActivity(hardActivityRef);
              }
            });
      } catch (Throwable throwable) {
        throw new IllegalStateException(throwable);
      }
    }
    return activityResult;
  }

  private void setActivityResultForActivity(final T activity) {
    checkState(Looper.myLooper() == Looper.getMainLooper(), "Must be called on the main thread!");
    checkNotNull(activity, "Activity wasn't created yet or already destroyed!");

    try {
      Field resultCodeField = Activity.class.getDeclaredField(FIELD_RESULT_CODE);
      resultCodeField.setAccessible(true);

      Field resultDataField = Activity.class.getDeclaredField(FIELD_RESULT_DATA);
      resultDataField.setAccessible(true);

      activityResult =
          new ActivityResult(
              (int) resultCodeField.get(activity), (Intent) resultDataField.get(activity));
    } catch (NoSuchFieldException e) {
      String msg =
          "Looks like the Android Activity class has changed its"
              + "private fields for mResultCode or mResultData. "
              + "Time to update the reflection code.";
      Log.e(TAG, msg, e);
      throw new RuntimeException(msg, e);
    } catch (IllegalAccessException e) {
      String msg = "Field mResultCode or mResultData is not accessible";
      Log.e(TAG, msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  /**
   * Helper method for running part of a method on the UI thread, blocking until it is complete.
   *
   * <p>Note: In most cases it is simpler to annotate the test method with {@link UiThreadTest}.
   *
   * <p>Use this method if you need to switch in and out of the UI thread within your method.
   *
   * @param runnable runnable containing test code in the {@link Runnable#run()} method
   * @see androidx.test.annotation.UiThreadTest
   */
  public void runOnUiThread(final Runnable runnable) throws Throwable {
    UiThreadStatement.runOnUiThread(runnable);
  }

  /**
   * <a href="http://junit.org/apidocs/org/junit/runners/model/Statement.html"><code>Statement
   * </code></a> that finishes the activity after the test was executed
   */
  private class ActivityStatement extends Statement {

    private final Statement base;

    public ActivityStatement(Statement base) {
      this.base = base;
    }

    @Override
    public void evaluate() throws Throwable {
      MonitoringInstrumentation instrumentation =
          ActivityTestRule.this.instrumentation instanceof MonitoringInstrumentation
              ? (MonitoringInstrumentation) ActivityTestRule.this.instrumentation
              : null;
      try {
        if (activityFactory != null && instrumentation != null) {
          instrumentation.interceptActivityUsing(activityFactory);
        }
        if (launchActivity) {
          launchActivity(getActivityIntent());
        }
        base.evaluate();
      } finally {
        if (instrumentation != null) {
          instrumentation.useDefaultInterceptingActivityFactory();
        }

        T hardActivityRef = activity.get();
        if (hardActivityRef != null) {
          finishActivity();
        }
        activityResult = null;
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycleCallback);
      }
    }
  }

  @VisibleForTesting
  WeakReference<T> makeWeakReference(T activity) {
    return new WeakReference<T>(activity);
  }

  /**
   * Activity lifecycle callback which ensures to release a reference on the activity under test
   * after lifecycle changes. This is done to ensure that we don't leak the original Activity under
   * test and at the same time have a reference to the currently visible activity.
   *
   * <p>Note: this callback is run on the main thread!
   */
  private class LifecycleCallback implements ActivityLifecycleCallback {
    @Override
    public void onActivityLifecycleChanged(Activity activity, Stage stage) {
      if (activityClass.isInstance(activity)) {
        if (Stage.RESUMED == stage) {
          ActivityTestRule.this.activity = makeWeakReference(activityClass.cast(activity));
        } else if (Stage.PAUSED == stage) {
          // If there is an activity result we save it
          if (activity.isFinishing() && activityResult != null) {
            setActivityResultForActivity(activityClass.cast(activity));
          }
        }
      }
    }
  }
}
