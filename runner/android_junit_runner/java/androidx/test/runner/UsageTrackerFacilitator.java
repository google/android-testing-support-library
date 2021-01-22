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


import androidx.annotation.NonNull;
import androidx.test.internal.runner.RunnerArgs;

/** @deprecated no-op, androidx.test no longer tracks analytics */
@Deprecated
public class UsageTrackerFacilitator {

  public UsageTrackerFacilitator(@NonNull RunnerArgs runnerArgs) {
  }

  public UsageTrackerFacilitator(boolean shouldTrackUsage) {
  }

  public boolean shouldTrackUsage() {
    return false;
  }

  public void trackUsage(String usage, String version) {
  }

  public void sendUsages() {
  }
}
