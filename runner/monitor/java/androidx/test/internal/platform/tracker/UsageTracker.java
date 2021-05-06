/*
 * Copyright (C) 2017 The Android Open Source Project
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

package androidx.test.internal.platform.tracker;

/**
 * Used by test infrastructure to report usage stats (optionally).
 *
 * <p>This interface should only be used by test infrastructure.
 */
public interface UsageTracker {

  /**
   * Indicates that a particular tool/api was used.
   *
   * <p>Usage will be dumped at the end of the instrumentation run.
   */
  void trackUsage(String usage, String version);

  /** Requests that all usages be sent. */
  void sendUsages();

  /** NoOp implementation. */
  class NoOpUsageTracker implements UsageTracker {
    @Override
    public void trackUsage(String unused, String unusedVersion) {}

    @Override
    public void sendUsages() {}
  }
}
