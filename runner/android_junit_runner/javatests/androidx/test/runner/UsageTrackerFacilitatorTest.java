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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import androidx.test.InstrumentationRegistry;
import androidx.test.internal.platform.tracker.UsageTracker;
import androidx.test.internal.runner.RunnerArgs;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link UsageTrackerFacilitator} */
@RunWith(AndroidJUnit4.class)
public class UsageTrackerFacilitatorTest {
  private static final String ARGUMENT_DISABLE_ANALYTICS = "disableAnalytics";
  private static final String VALUE_DISABLE_ANALYTICS_TRUE = "true";
  private static final String VALUE_DISABLE_ANALYTICS_FALSE = "false";

  static final String ARGUMENT_ORCHESTRATOR_SERVICE = "orchestratorService";
  static final String ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR = "listTestsForOrchestrator";

  private UsageTrackerFacilitator analyticsFacilitator;

  @Mock private UsageTracker usageTracker;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void analyticsDisabled_shouldTrackUsage_ReturnsFalse() {
    analyticsFacilitator = new UsageTrackerFacilitator(withAnalyticsDisabled());

    assertThat(analyticsFacilitator.shouldTrackUsage(), equalTo(false));
  }

  @Test
  public void analyticsEnabledShouldTrackUsage_ReturnsTrue() {
    analyticsFacilitator = new UsageTrackerFacilitator(withAnalyticsEnabled());

    assertThat(analyticsFacilitator.shouldTrackUsage(), equalTo(true));
  }

  @Test
  public void analyticsEnabledDoesTrackUsage() {
    analyticsFacilitator = new UsageTrackerFacilitator(withAnalyticsEnabled());
    analyticsFacilitator.registerUsageTracker(usageTracker);

    String usage = "SomeComponent";
    String version = "1.2.3";
    analyticsFacilitator.trackUsage(usage, version);
    analyticsFacilitator.sendUsages();

    verify(usageTracker).trackUsage(eq(usage), eq(version));
    verify(usageTracker).sendUsages();
  }

  @Test
  public void analyticsDisabledDoesNotTrackUsage() {
    analyticsFacilitator = new UsageTrackerFacilitator(withAnalyticsDisabled());
    analyticsFacilitator.registerUsageTracker(usageTracker);

    String usage = "SomeComponent";
    String version = "1.2.3";
    analyticsFacilitator.trackUsage(usage, version);
    analyticsFacilitator.sendUsages();

    verify(usageTracker, never()).trackUsage(eq(usage), eq(version));
    verify(usageTracker, never()).sendUsages();
  }

  @Test
  public void orchestratorCollectionRunDoesTrackUsage() {
    analyticsFacilitator = new UsageTrackerFacilitator(withOrchestratorCollectionRun());
    analyticsFacilitator.registerUsageTracker(usageTracker);

    String usage = "SomeComponent";
    String version = "1.2.3";
    analyticsFacilitator.trackUsage(usage, version);
    analyticsFacilitator.sendUsages();

    verify(usageTracker).trackUsage(eq(usage), eq(version));
    verify(usageTracker).sendUsages();
  }

  @Test
  public void orchestratorSecondaryRunDoesNotTrackUsage() {
    analyticsFacilitator = new UsageTrackerFacilitator(withOrchestratorSecondaryRun());
    analyticsFacilitator.registerUsageTracker(usageTracker);

    String usage = "SomeComponent";
    String version = "1.2.3";
    analyticsFacilitator.trackUsage(usage, version);
    analyticsFacilitator.sendUsages();

    verify(usageTracker, never()).trackUsage(eq(usage), eq(version));
    verify(usageTracker, never()).sendUsages();
  }

  private static RunnerArgs withOrchestratorCollectionRun() {
    Bundle bundle = new Bundle();
    bundle.putString(ARGUMENT_ORCHESTRATOR_SERVICE, "someOrchestratorValue");
    bundle.putString(ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR, "true");
    return new RunnerArgs.Builder()
        .fromBundle(InstrumentationRegistry.getInstrumentation(), bundle)
        .build();
  }

  private static RunnerArgs withOrchestratorSecondaryRun() {
    Bundle bundle = new Bundle();
    bundle.putString(ARGUMENT_ORCHESTRATOR_SERVICE, "someOrchestratorValue");
    bundle.putString(ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR, "false");
    return new RunnerArgs.Builder()
        .fromBundle(InstrumentationRegistry.getInstrumentation(), bundle)
        .build();
  }

  private static RunnerArgs withAnalyticsDisabled(String disabledBooleanValue) {
    Bundle bundle = new Bundle();
    bundle.putString(ARGUMENT_DISABLE_ANALYTICS, disabledBooleanValue);
    return new RunnerArgs.Builder()
        .fromBundle(InstrumentationRegistry.getInstrumentation(), bundle)
        .build();
  }

  private static RunnerArgs withAnalyticsDisabled() {
    return withAnalyticsDisabled(VALUE_DISABLE_ANALYTICS_TRUE);
  }

  private static RunnerArgs withAnalyticsEnabled() {
    return withAnalyticsDisabled(VALUE_DISABLE_ANALYTICS_FALSE);
  }
}
