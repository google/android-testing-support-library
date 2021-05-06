/*
 * Copyright (C) 2014 The Android Open Source Project
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

package androidx.test.internal.runner.lifecycle;

import static androidx.test.internal.util.Checks.checkNotNull;

import android.app.Activity;
import android.os.Looper;
import android.util.Log;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/** The lifecycle monitor implementation. */
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  private static final String TAG = "LifecycleMonitor";
  private final boolean declawThreadCheck;

  public ActivityLifecycleMonitorImpl() {
    this(false);
  }

  // For Testing
  public ActivityLifecycleMonitorImpl(boolean declawThreadCheck) {
    this.declawThreadCheck = declawThreadCheck;
  }

  // Accessed from any thread.
  private final List<ActivityLifecycleCallback> callbacks = new ArrayList<>();

  // Only accessed on main thread.
  private List<ActivityStatus> activityStatuses = new ArrayList<ActivityStatus>();

  @Override
  public void addLifecycleCallback(ActivityLifecycleCallback callback) {
    // there will never be too many callbacks, so iterating over a list will probably
    // be faster then the constant time costs of setting up and maintaining a map.
    checkNotNull(callback);

    synchronized (callbacks) {
      if (!callbacks.contains(callback)) {
        callbacks.add(callback);
      }
    }
  }

  @Override
  public void removeLifecycleCallback(ActivityLifecycleCallback callback) {
    checkNotNull(callback);

    synchronized (callbacks) {
      callbacks.remove(callback);
    }
  }

  @Override
  public Stage getLifecycleStageOf(Activity activity) {
    checkMainThread();
    checkNotNull(activity);
    Iterator<ActivityStatus> statusIterator = activityStatuses.iterator();
    while (statusIterator.hasNext()) {
      ActivityStatus status = statusIterator.next();
      Activity statusActivity = status.activityRef.get();
      if (null == statusActivity) {
        statusIterator.remove();
      } else if (activity == statusActivity) {
        return status.lifecycleStage;
      }
    }
    throw new IllegalArgumentException("Unknown activity: " + activity);
  }

  @Override
  public Collection<Activity> getActivitiesInStage(Stage stage) {
    checkMainThread();
    checkNotNull(stage);

    List<Activity> activities = new ArrayList<Activity>();
    Iterator<ActivityStatus> statusIterator = activityStatuses.iterator();
    while (statusIterator.hasNext()) {
      ActivityStatus status = statusIterator.next();
      Activity statusActivity = status.activityRef.get();
      if (null == statusActivity) {
        statusIterator.remove();
      } else if (stage == status.lifecycleStage) {
        activities.add(statusActivity);
      }
    }

    return activities;
  }

  /**
   * Called by the runner after a particular onXXX lifecycle method has been called on a given
   * activity.
   */
  public void signalLifecycleChange(Stage stage, Activity activity) {
    // there are never too many activities in existence in an application - so we keep
    // track of everything in a single list.
    Log.d(TAG, "Lifecycle status change: " + activity + " in: " + stage);

    boolean needsAdd = true;
    Iterator<ActivityStatus> statusIterator = activityStatuses.iterator();
    while (statusIterator.hasNext()) {
      ActivityStatus status = statusIterator.next();
      Activity statusActivity = status.activityRef.get();
      if (null == statusActivity) {
        statusIterator.remove();
      } else if (activity == statusActivity) {
        needsAdd = false;
        status.lifecycleStage = stage;
      }
    }

    if (needsAdd) {
      activityStatuses.add(new ActivityStatus(activity, stage));
    }

    // grab a copy to avoid holding lock while iterating
    List<ActivityLifecycleCallback> callbackCopy;
    synchronized (callbacks) {
      callbackCopy = new ArrayList<>(callbacks);
    }
    for (ActivityLifecycleCallback callback : callbacks) {
      callback.onActivityLifecycleChanged(activity, stage);
    }
  }

  private void checkMainThread() {
    if (declawThreadCheck) {
      return;
    }

    if (!Thread.currentThread().equals(Looper.getMainLooper().getThread())) {
      throw new IllegalStateException("Querying activity state off main thread is not allowed.");
    }
  }

  private static class ActivityStatus {
    private final WeakReference<Activity> activityRef;
    private Stage lifecycleStage;

    ActivityStatus(Activity activity, Stage stage) {
      this.activityRef = new WeakReference<Activity>(checkNotNull(activity));
      this.lifecycleStage = checkNotNull(stage);
    }
  }
}
