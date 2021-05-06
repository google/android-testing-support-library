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

package androidx.test.runner;

import static androidx.test.internal.util.Checks.checkNotNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import androidx.test.internal.platform.tracker.UsageTracker;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry;
import androidx.test.internal.runner.RunnerArgs;

/**
 * Helper class to enable/disable usage tracker in the runner. For details on how AndroidJUnitRunner
 * tracks usage, see <a
 * href="https://android.github.io/android-test/docs/espresso/setup/index.html#analytics">
 * AndroidJUnitRunner Analytics</a>
 */
public class UsageTrackerFacilitator implements UsageTracker {
  private static final String TAG = "UsageTrackerFacilitator";

  private final boolean shouldTrackUsage;

  public UsageTrackerFacilitator(@NonNull RunnerArgs runnerArgs) {
    checkNotNull(runnerArgs, "runnerArgs cannot be null!");

    // If we are being orchestrated, only report for test collection, not subsequent runs.
    if (runnerArgs.orchestratorService != null) {
      shouldTrackUsage = !runnerArgs.disableAnalytics && runnerArgs.listTestsForOrchestrator;
    } else {
      shouldTrackUsage = !runnerArgs.disableAnalytics;
    }
  }

  public UsageTrackerFacilitator(boolean shouldTrackUsage) {
    this.shouldTrackUsage = shouldTrackUsage;
  }

  public boolean shouldTrackUsage() {
    return shouldTrackUsage;
  }

  public void registerUsageTracker(@Nullable UsageTracker usageTracker) {
    if (usageTracker != null && shouldTrackUsage()) {
      Log.i(TAG, "Usage tracking enabled");
      UsageTrackerRegistry.registerInstance(usageTracker);
      return;
    }
    // Even though this is the default usage tracker we should not rely on internal
    // implementation details of UsageTrackerRegistry
    Log.i(TAG, "Usage tracking disabled");
    UsageTrackerRegistry.registerInstance(new NoOpUsageTracker());
  }

  @Override
  public void trackUsage(String usage, String version) {
    if (shouldTrackUsage()) {
      UsageTrackerRegistry.getInstance().trackUsage(usage, version);
    }
  }

  @Override
  public void sendUsages() {
    if (shouldTrackUsage()) {
      UsageTrackerRegistry.getInstance().sendUsages();
    }
  }
}
