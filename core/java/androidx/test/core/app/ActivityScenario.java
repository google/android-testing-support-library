/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.test.core.app;

import static androidx.test.internal.util.Checks.checkMainThread;
import static androidx.test.internal.util.Checks.checkNotMainThread;
import static androidx.test.internal.util.Checks.checkNotNull;
import static androidx.test.internal.util.Checks.checkState;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import androidx.lifecycle.Lifecycle.Event;
import androidx.lifecycle.Lifecycle.State;
import android.content.Intent;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import android.util.Log;
import androidx.test.internal.platform.ServiceLoaderWrapper;
import androidx.test.internal.platform.app.ActivityInvoker;
import androidx.test.internal.platform.os.ControlledLooper;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.Closeable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ActivityScenario provides APIs to start and drive an Activity's lifecycle state for testing. It
 * works with arbitrary activities and works consistently across different versions of the Android
 * framework.
 *
 * <p>The ActivityScenario API uses {@link State} extensively. If you are unfamiliar with {@link
 * androidx.lifecycle} components, please read <a
 * href="https://developer.android.com/topic/libraries/architecture/lifecycle#lc">lifecycle</a>
 * before starting. It is crucial to understand the difference between {@link State} and {@link
 * Event}.
 *
 * <p>{@link ActivityScenario#moveToState(State)} allows you to transition your Activity's state to
 * {@link State#CREATED}, {@link State#STARTED}, {@link State#RESUMED}, or {@link State#DESTROYED}.
 * There are two paths for an Activity to reach {@link State#CREATED}: after {@link Event#ON_CREATE}
 * happens but before {@link Event#ON_START}, or after {@link Event#ON_STOP}. ActivityScenario
 * always moves the Activity's state using the second path. The same applies to {@link
 * State#STARTED}.
 *
 * <p>{@link State#DESTROYED} is the terminal state. You cannot move your Activity to other state
 * once it reaches to that state. If you want to test recreation of Activity instance, use {@link
 * #recreate()}.
 *
 * <p>ActivityScenario does't clean up device state automatically and may leave the activity keep
 * running after the test finishes. Call {@link #close()} in your test to clean up the state or use
 * try-with-resources statement. This is optional but highly recommended to improve the stability of
 * your tests. Also, consider using {@link androidx.test.ext.junit.rules.ActivityScenarioRule}.
 *
 * <p>This class is a replacement of ActivityController in Robolectric and ActivityTestRule in ATSL.
 *
 * <p>Following are the example of common use cases.
 *
 * <pre>{@code
 * Before:
 *   MyActivity activity = Robolectric.setupActivity(MyActivity.class);
 *   assertThat(activity.getSomething()).isEqualTo("something");
 *
 * After:
 *   try(ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class)) {
 *     scenario.onActivity(activity -> {
 *       assertThat(activity.getSomething()).isEqualTo("something");
 *     });
 *   }
 *
 * Before:
 *   ActivityController<MyActivity> controller = Robolectric.buildActivity(MyActivity.class);
 *   controller.create().start().resume();  // Moves the activity state to State.RESUMED.
 *   controller.pause();    // Moves the activity state to State.STARTED. (ON_PAUSE is an event).
 *   controller.stop();     // Moves the activity state to State.CREATED. (ON_STOP is an event).
 *   controller.destroy();  // Moves the activity state to State.DESTROYED.
 *
 * After:
 *   try(ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class)) {
 *     scenario.moveToState(State.RESUMED);    // Moves the activity state to State.RESUMED.
 *     scenario.moveToState(State.STARTED);    // Moves the activity state to State.STARTED.
 *     scenario.moveToState(State.CREATED);    // Moves the activity state to State.CREATED.
 *     scenario.moveToState(State.DESTROYED);  // Moves the activity state to State.DESTROYED.
 *   }
 * }</pre>
 */

/* this is just a test, thank you for this awesome framework*/

public final class ActivityScenario<A extends Activity> implements AutoCloseable, Closeable {
  private static final String TAG = ActivityScenario.class.getSimpleName();

  /**
   * The timeout for {@link #waitForActivityToBecomeAnyOf} method. If an Activity doesn't become
   * requested state after the timeout, we will throw {@link AssertionError} to fail tests.
   */
  private static final long TIMEOUT_MILLISECONDS = 45000;

  /**
   * A map to lookup steady {@link State} by {@link Stage}. Transient stages such as {@link
   * Stage#CREATED}, {@link Stage#STARTED} and {@link Stage#RESTARTED} are not included in the map.
   */
  private static final Map<Stage, State> STEADY_STATES = new EnumMap<>(Stage.class);

  static {
    STEADY_STATES.put(Stage.RESUMED, State.RESUMED);
    STEADY_STATES.put(Stage.PAUSED, State.STARTED);
    STEADY_STATES.put(Stage.STOPPED, State.CREATED);
    STEADY_STATES.put(Stage.DESTROYED, State.DESTROYED);
  }

  /** A lock that is used to block the main thread until the Activity becomes a requested state. */
  private final ReentrantLock lock = new ReentrantLock();

  /** A condition object to be notified when the activity state changes. */
  private final Condition stateChangedCondition = lock.newCondition();

  /** An intent to start a testing Activity. */
  private final Intent startActivityIntent;

  /** An ActivityInvoker to use. Implementation class can be configured by service provider. */
  private final ActivityInvoker activityInvoker =
      ServiceLoaderWrapper.loadSingleService(
          ActivityInvoker.class, () -> new InstrumentationActivityInvoker());

  private final ControlledLooper controlledLooper =
      ServiceLoaderWrapper.loadSingleService(
          ControlledLooper.class, () -> ControlledLooper.NO_OP_CONTROLLED_LOOPER);

  /**
   * A current activity stage. This variable is updated by {@link ActivityLifecycleMonitor} from the
   * main thread.
   */
  @GuardedBy("lock")
  private Stage currentActivityStage = Stage.PRE_ON_CREATE;

  /**
   * A current activity. This variable is updated by {@link ActivityLifecycleMonitor} from the main
   * thread.
   */
  @GuardedBy("lock")
  @Nullable
  private A currentActivity;

  /** Private constructor. Use {@link #launch} to instantiate this class. */
  private ActivityScenario(Intent startActivityIntent) {
    this.startActivityIntent = checkNotNull(startActivityIntent);
  }

  /** Private constructor. Use {@link #launch} to instantiate this class. */
  private ActivityScenario(Class<A> activityClass) {
    this.startActivityIntent =
        checkNotNull(activityInvoker.getIntentForActivity(checkNotNull(activityClass)));
  }

  /**
   * Launches an activity of a given class and constructs ActivityScenario with the activity. Waits
   * for the lifecycle state transitions to be complete. Typically the initial state of the activity
   * is {@link State#RESUMED} but can be in another state. For instance, if your activity calls
   * {@link Activity#finish} from your {@link Activity#onCreate}, the state is {@link
   * State#DESTROYED} when this method returns.
   *
   * <p>If you need to supply parameters to the start activity intent, use {@link #launch(Intent)}.
   *
   * <p>This method cannot be called from the main thread except in Robolectric tests.
   *
   * @param activityClass an activity class to launch
   * @throws AssertionError if the lifecycle state transition never completes within the timeout
   * @return ActivityScenario which you can use to make further state transitions
   */
  public static <A extends Activity> ActivityScenario<A> launch(Class<A> activityClass) {
    ActivityScenario<A> scenario = new ActivityScenario<>(checkNotNull(activityClass));
    scenario.launchInternal(/*activityOptions=*/ null);
    return scenario;
  }

  /**
   * @see #launch(Class)
   * @param activityOptions an activity options bundle to be passed along with the intent to start
   *     activity.
   */
  public static <A extends Activity> ActivityScenario<A> launch(
      Class<A> activityClass, @Nullable Bundle activityOptions) {
    ActivityScenario<A> scenario = new ActivityScenario<>(checkNotNull(activityClass));
    scenario.launchInternal(activityOptions);
    return scenario;
  }

  /**
   * Launches an activity by a given intent and constructs ActivityScenario with the activity. Waits
   * for the lifecycle state transitions to be complete. Typically the initial state of the activity
   * is {@link State#RESUMED} but can be in another state. For instance, if your activity calls
   * {@link Activity#finish} from your {@link Activity#onCreate}, the state is {@link
   * State#DESTROYED} when this method returns.
   *
   * <p>This method cannot be called from the main thread except in Robolectric tests.
   *
   * @param startActivityIntent an intent to start the activity
   * @throws AssertionError if the lifecycle state transition never completes within the timeout
   * @return ActivityScenario which you can use to make further state transitions
   */
  public static <A extends Activity> ActivityScenario<A> launch(Intent startActivityIntent) {
    ActivityScenario<A> scenario = new ActivityScenario<>(checkNotNull(startActivityIntent));
    scenario.launchInternal(/*activityOptions=*/ null);
    return scenario;
  }

  /**
   * @see #launch(Intent)
   * @param activityOptions an activity options bundle to be passed along with the intent to start
   *     activity.
   */
  public static <A extends Activity> ActivityScenario<A> launch(
      Intent startActivityIntent, @Nullable Bundle activityOptions) {
    ActivityScenario<A> scenario = new ActivityScenario<>(checkNotNull(startActivityIntent));
    scenario.launchInternal(activityOptions);
    return scenario;
  }

  /**
   * An internal helper method to perform initial launch operation for the given scenario instance
   * along with preconditions checks around device's configuration.
   *
   * @param activityOptions activity options bundle to be passed when launching this activity
   */
  private void launchInternal(@Nullable Bundle activityOptions) {
    checkState(
        Settings.System.getInt(
                getInstrumentation().getTargetContext().getContentResolver(),
                Settings.Global.ALWAYS_FINISH_ACTIVITIES,
                0)
            == 0,
        "\"Don't keep activities\" developer options must be disabled for ActivityScenario");

    checkNotMainThread();
    getInstrumentation().waitForIdleSync();

    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(activityLifecycleObserver);

    // prefer the single argument variant for startActivity for backwards compatibility with older
    // Robolectric versions
    if (activityOptions == null) {
      activityInvoker.startActivity(startActivityIntent);
    } else {
      activityInvoker.startActivity(startActivityIntent, activityOptions);
    }

    // Accept any steady states. An activity may start another activity in its onCreate method. Such
    // an activity goes back to created or started state immediately after it is resumed.
    waitForActivityToBecomeAnyOf(STEADY_STATES.values().toArray(new State[0]));
  }

  /**
   * Finishes the managed activity and cleans up device's state. This method blocks execution until
   * the activity becomes {@link State#DESTROYED}.
   *
   * <p>It is highly recommended to call this method after you test is done to keep the device state
   * clean although this is optional.
   *
   * <p>You may call this method more than once. If the activity has been finished already, this
   * method does nothing.
   *
   * <p>Avoid calling this method directly. Consider one of the following options instead:
   *
   * <pre>{@code
   *  Option 1, use try-with-resources:
   *
   *  try (ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class)) {
   *    // Your test code goes here.
   *  }
   *
   *  Option 2, use ActivityScenarioRule:
   *
   * }{@literal @Rule} {@code
   *  public ActivityScenarioRule<MyActivity> rule = new ActivityScenarioRule<>(MyActivity.class);
   *
   * }{@literal @Test}{@code
   *  public void myTest() {
   *    ActivityScenario<MyActivity> scenario = rule.getScenario();
   *    // Your test code goes here.
   *  }
   * }</pre>
   */
  @Override
  public void close() {
    moveToState(State.DESTROYED);
    ActivityLifecycleMonitorRegistry.getInstance()
        .removeLifecycleCallback(activityLifecycleObserver);
  }

  /**
   * Blocks the current thread until activity transition completes and its state becomes one of a
   * given state.
   */
  private void waitForActivityToBecomeAnyOf(State... expectedStates) {
    // Wait for idle sync otherwise we might hit transient state.
    getInstrumentation().waitForIdleSync();

    Set<State> expectedStateSet = new HashSet<>(Arrays.asList(expectedStates));
    lock.lock();
    try {
      if (expectedStateSet.contains(STEADY_STATES.get(currentActivityStage))) {
        return;
      }

      long now = System.currentTimeMillis();
      long deadline = now + TIMEOUT_MILLISECONDS;
      while (now < deadline
          && !expectedStateSet.contains(STEADY_STATES.get(currentActivityStage))) {
        stateChangedCondition.await(deadline - now, TimeUnit.MILLISECONDS);
        now = System.currentTimeMillis();
      }

      if (!expectedStateSet.contains(STEADY_STATES.get(currentActivityStage))) {
        throw new AssertionError(
            String.format(
                "Activity never becomes requested state \"%s\" (last lifecycle transition ="
                    + " \"%s\")",
                expectedStateSet, currentActivityStage));
      }
    } catch (InterruptedException e) {
      throw new AssertionError(
          String.format(
              "Activity never becomes requested state \"%s\" (last lifecycle transition = \"%s\")",
              expectedStateSet, currentActivityStage),
          e);
    } finally {
      lock.unlock();
    }
  }

  /** Observes an Activity lifecycle change events and updates ActivityScenario's internal state. */
  private final ActivityLifecycleCallback activityLifecycleObserver =
      new ActivityLifecycleCallback() {
        @Override
        public void onActivityLifecycleChanged(Activity activity, Stage stage) {
          if (!activityMatchesIntent(startActivityIntent, activity)) {
            Log.v(
                TAG,
                String.format(
                    "Activity lifecycle changed event received but ignored because the intent does"
                        + " not match. startActivityIntent=%s, activity.getIntent()=%s,"
                        + " activity=%s",
                    startActivityIntent, activity.getIntent(), activity));
            return;
          }
          lock.lock();
          try {
            switch (currentActivityStage) {
              case PRE_ON_CREATE:
              case DESTROYED:
                // The initial state (or after destroyed when the activity is being recreated)
                // transition must be to CREATED. Ignore events with non-created stage, which are
                // likely come from activities that the previous test starts and doesn't clean up.
                if (stage != Stage.CREATED) {
                  Log.v(
                      TAG,
                      String.format(
                          "Activity lifecycle changed event received but ignored because the"
                              + " reported transition was not ON_CREATE while the last known"
                              + " transition was %s",
                          currentActivityStage));
                  return;
                }
                break;

              default:
                // Make sure the received event is about the activity which this ActivityScenario
                // is monitoring. The Android framework may start multiple instances of a same
                // activity class and intent at a time. Also, there can be a race condition between
                // an activity that is used by the previous test and being destroyed and an activity
                // that is being resumed.
                if (currentActivity != activity) {
                  Log.v(
                      TAG,
                      String.format(
                          "Activity lifecycle changed event received but ignored because the"
                              + " activity instance does not match. currentActivity=%s,"
                              + " receivedActivity=%s",
                          currentActivity, activity));
                  return;
                }
                break;
            }

            // Update the internal state to be synced with the Android system. Don't hold activity
            // reference if the new state is destroyed. It's not good idea to access to destroyed
            // activity since the system may reuse the instance or want to garbage collect.
            currentActivityStage = stage;
            currentActivity = (A) (stage != Stage.DESTROYED ? activity : null);

            Log.v(
                TAG,
                String.format(
                    "Update currentActivityStage to %s, currentActivity=%s",
                    currentActivityStage, currentActivity));

            stateChangedCondition.signal();
          } finally {
            lock.unlock();
          }
        }
      };

  /** Determine if the intent matches the given activity. */
  private static boolean activityMatchesIntent(
      Intent startActivityIntent, Activity launchedActivity) {
    // The logic here is almost the same as Intent.filterEquals
    // but we need to handle case where startActivityIntent does not have component specified
    // (aka is implicit intent). The launchedActivity intent will always have component specified,
    // since
    // the framework populates it

    Intent activityIntent = launchedActivity.getIntent();
    if (!equals(startActivityIntent.getAction(), activityIntent.getAction())) {
      return false;
    }
    if (!equals(startActivityIntent.getData(), activityIntent.getData())) {
      return false;
    }
    if (!equals(startActivityIntent.getType(), activityIntent.getType())) {
      return false;
    }
    if (!equals(startActivityIntent.getPackage(), activityIntent.getPackage())) {
      return false;
    }
    if (startActivityIntent.getComponent() != null) {
      if (!equals(startActivityIntent.getComponent(), activityIntent.getComponent())) {
        return false;
      }
    }
    if (!equals(startActivityIntent.getCategories(), activityIntent.getCategories())) {
      return false;
    }
    if (VERSION.SDK_INT >= 29) {
      if (!equals(startActivityIntent.getIdentifier(), activityIntent.getIdentifier())) {
        return false;
      }
    }

    return true;
  }

  // reimplementation of Objects.equals since it is only available on APIs >= 19
  private static boolean equals(Object a, Object b) {
    return (a == b) || (a != null && a.equals(b));
  }

  /**
   * ActivityState is a state class that holds a snapshot of an Activity's current state and a
   * reference to the Activity.
   */
  private static class ActivityState<A extends Activity> {
    @Nullable final A activity;
    @Nullable final State state;
    final Stage stage;

    ActivityState(@Nullable A activity, @Nullable State state, Stage stage) {
      this.activity = activity;
      this.state = state;
      this.stage = stage;
    }
  }

  private ActivityState<A> getCurrentActivityState() {
    getInstrumentation().waitForIdleSync();
    lock.lock();
    try {
      return new ActivityState<>(
          currentActivity, STEADY_STATES.get(currentActivityStage), currentActivityStage);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Moves Activity state to a new state.
   *
   * <p>If a new state and current state are the same, it does nothing. It accepts {@link
   * State#CREATED}, {@link State#STARTED}, {@link State#RESUMED}, and {@link State#DESTROYED}.
   *
   * <p>{@link State#DESTROYED} is the terminal state. You cannot move the state to other state
   * after the activity reaches that state.
   *
   * <p>The activity must be at the top of the back stack (excluding internal facilitator activities
   * started by this library), otherwise {@link AssertionError} may be thrown. If the activity
   * starts another activity (such as DialogActivity), make sure you close these activities and
   * bring back the original activity foreground before you call this method.
   *
   * <p>This method cannot be called from the main thread except in Robolectric tests.
   *
   * @throws IllegalArgumentException if unsupported {@code newState} is given
   * @throws IllegalStateException if Activity is destroyed, finished or finishing
   * @throws AssertionError if Activity never becomes requested state
   */
  public ActivityScenario<A> moveToState(State newState) {
    checkNotMainThread();
    getInstrumentation().waitForIdleSync();

    ActivityState<A> currentState = getCurrentActivityState();
    checkNotNull(
        currentState.state,
        String.format("Current state was null unexpectedly. Last stage = %s", currentState.stage));
    if (currentState.state == newState) {
      return this;
    }
    checkState(
        currentState.state != State.DESTROYED && currentState.activity != null,
        String.format(
            "Cannot move to state \"%s\" since the Activity has been destroyed already", newState));

    switch (newState) {
      case CREATED:
        activityInvoker.stopActivity(currentState.activity);
        break;
      case STARTED:
        // ActivityInvoker#pauseActivity only accepts resumed or paused activity. Move the state to
        // resumed first.
        moveToState(State.RESUMED);
        activityInvoker.pauseActivity(currentState.activity);
        break;
      case RESUMED:
        activityInvoker.resumeActivity(currentState.activity);
        break;
      case DESTROYED:
        activityInvoker.finishActivity(currentState.activity);
        break;
      default:
        throw new IllegalArgumentException(
            String.format("A requested state \"%s\" is not supported", newState));
    }

    waitForActivityToBecomeAnyOf(newState);
    return this;
  }

  /**
   * Recreates the Activity.
   *
   * <p>A current Activity will be destroyed after its data is saved into {@link android.os.Bundle}
   * with {@link Activity#onSaveInstanceState(Bundle)}, then it creates a new Activity with the
   * saved Bundle. After this method call, it is ensured that the Activity state goes back to the
   * same state as its previous state.
   *
   * <p>This method cannot be called from the main thread except in Robolectric tests.
   *
   * @throws IllegalStateException if Activity is destroyed, finished or finishing
   * @throws AssertionError if Activity never be re-created
   */
  public ActivityScenario<A> recreate() {
    checkNotMainThread();
    getInstrumentation().waitForIdleSync();

    final ActivityState<A> prevActivityState = getCurrentActivityState();
    checkNotNull(prevActivityState.activity);
    checkNotNull(prevActivityState.state);

    // Move the state to RESUMED before starting re-creation and manually move the state to its
    // original state after the re-creation. This is because Activity#recreate's behavior differs
    // by Android framework version. See InstrumentationActivityInvoker#recreateActivity for
    // details.
    moveToState(State.RESUMED);
    activityInvoker.recreateActivity(prevActivityState.activity);

    ActivityState<A> activityState;
    long now = System.currentTimeMillis();
    long deadline = now + TIMEOUT_MILLISECONDS;
    do {
      waitForActivityToBecomeAnyOf(State.RESUMED);
      now = System.currentTimeMillis();
      activityState = getCurrentActivityState();
    } while (now < deadline && activityState.activity == prevActivityState.activity);
    if (activityState.activity == prevActivityState.activity) {
      throw new IllegalStateException("Requested a re-creation of Activity but didn't happen");
    }

    moveToState(prevActivityState.state);

    return this;
  }

  /**
   * ActivityAction interface should be implemented by any class whose instances are intended to be
   * executed by the main thread. An Activity that is instrumented by the ActivityScenario is passed
   * to {@link ActivityAction#perform} method.
   *
   * <pre>{@code
   * Example:
   *   ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class);
   *   scenario.onActivity(activity -> {
   *     assertThat(activity.getSomething()).isEqualTo("something");
   *   });
   * }</pre>
   *
   * <p>You should never keep the Activity reference. It should only be accessed in {@link
   * ActivityAction#perform} scope for two reasons: 1) Android framework may re-create the Activity
   * during lifecycle changes, your holding reference might be stale. 2) It increases the reference
   * counter and it may affect to the framework behavior, especially after you finish the Activity.
   *
   * <pre>{@code
   * Bad Example:
   *   ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class);
   *   final MyActivity[] myActivityHolder = new MyActivity[1];
   *   scenario.onActivity(activity -> {
   *     myActivityHolder[0] = activity;
   *   });
   *   assertThat(myActivityHolder[0].getSomething()).isEqualTo("something");
   * }</pre>
   */
  public interface ActivityAction<A extends Activity> {
    /**
     * This method is invoked on the main thread with the reference to the Activity.
     *
     * @param activity an Activity instrumented by the {@link ActivityScenario}. It never be null.
     */
    void perform(A activity);
  }

  /**
   * Runs a given {@code action} on the current Activity's main thread.
   *
   * <p>Note that you should never keep Activity reference passed into your {@code action} because
   * it can be recreated at anytime during state transitions.
   *
   * @throws IllegalStateException if Activity is destroyed, finished or finishing
   */
  public ActivityScenario<A> onActivity(final ActivityAction<A> action) {
    // A runnable to perform given ActivityAction. This runnable should be invoked from the
    // application main thread.
    Runnable runnableAction =
        () -> {
          checkMainThread();

          lock.lock();
          try {
            checkNotNull(
                currentActivity, "Cannot run onActivity since Activity has been destroyed already");
            action.perform(currentActivity);
          } finally {
            lock.unlock();
          }
        };

    if (Looper.myLooper() == Looper.getMainLooper()) {
      // execute any queued work on main looper, to make behavior consistent between running
      // on Robolectric with paused main looper and instrumentation
      controlledLooper.drainMainThreadUntilIdle();
      runnableAction.run();
    } else {
      getInstrumentation().waitForIdleSync();
      getInstrumentation().runOnMainSync(runnableAction);
    }

    return this;
  }

  /**
   * Waits for the activity to be finished and returns the activity result.
   *
   * <p>Note: This method doesn't call {@link Activity#finish()}. The activity must be finishing or
   * finished otherwise this method will throws runtime exception after the timeout.
   *
   * <pre>{@code
   * Example:
   *   ActivityScenario<MyActivity> scenario = ActivityScenario.launch(MyActivity.class);
   *   // Let's say MyActivity has a button that finishes itself.
   *   onView(withId(R.id.finish_button)).perform(click());
   *   assertThat(scenario.getResult().getResultCode()).isEqualTo(Activity.RESULT_OK);
   * }</pre>
   *
   * @return activity result of the activity that managed by this scenario class.
   */
  public ActivityResult getResult() {
    return activityInvoker.getActivityResult();
  }

  /**
   * Returns the current activity state. The possible states are {@link State#CREATED}, {@link
   * State#STARTED}, {@link State#RESUMED}, and {@link State#DESTROYED}.
   *
   * <p>This method cannot be called from the main thread except in Robolectric tests.
   */
  public State getState() {
    ActivityState<A> currentActivityState = getCurrentActivityState();
    return checkNotNull(
        currentActivityState.state,
        "Could not get current state of activity %s due to the transition is incomplete. Current"
            + " stage = %s",
        currentActivityState.activity,
        currentActivityState.stage);
  }
}
