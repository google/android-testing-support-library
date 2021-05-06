package androidx.test.ext.junit.rules;

import android.app.Activity;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.testing.RecreationRecordingActivity;
import androidx.test.ext.junit.rules.LazyActivityScenarioRule.DisableRuleException;
import androidx.test.ext.junit.rules.LazyActivityScenarioRule.NoActivityLaunchedException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import static com.google.common.truth.Truth.assertThat;

/** Tests for {@link LazyActivityScenarioRule} */
@RunWith(AndroidJUnit4.class)
public final class LazyActivityScenarioRuleTest {

  @Rule
  public LazyActivityScenarioRule<RecreationRecordingActivity> activityScenarioRule =
          new LazyActivityScenarioRule<>(RecreationRecordingActivity.class, null);

  // TODO: Refactor tests to use assertThrows when JUnit is updated to version 4.13
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void activityShouldBeResumedAutomatically() {
    activityScenarioRule
            .getScenario()
            .onActivity(
                    activity -> {
                      assertThat(lastLifeCycleTransition(activity)).isEqualTo(Stage.RESUMED);
                      assertThat(activity.getNumberOfRecreations()).isEqualTo(0);
                    });
  }

  @Test
  public void recreateActivityShouldWork() {
    activityScenarioRule.getScenario().recreate();
    activityScenarioRule
            .getScenario()
            .onActivity(
                    activity -> {
                      assertThat(lastLifeCycleTransition(activity)).isEqualTo(Stage.RESUMED);
                      assertThat(activity.getNumberOfRecreations()).isEqualTo(1);
                    });
  }

  @Test
  public void activityCanBeDestroyedManually() {
    activityScenarioRule.getScenario().moveToState(Lifecycle.State.DESTROYED);
  }

  @Test
  public void whenNoGetScenarioCall_ShouldThrowNoActivityLaunchedException() {
    expectedException.expect(NoActivityLaunchedException.class);
    expectedException.expectMessage("No activities found. Did you remember to call getScenario() to " +
            "launch the activity under test.");
  }

  @Test
  public void whenDisabledGetScenarioCall_ShouldThrowDisableRuleException() {
    expectedException.expect(DisableRuleException.class);
    expectedException.expectMessage("Rule has been disabled. Calling getScenario() is not allowed.");
    activityScenarioRule.disable();
    activityScenarioRule.getScenario();
  }

  @Test
  public void whenGetScenarioCalledDisable_ShouldThrowDisableRuleException() {
    expectedException.expect(DisableRuleException.class);
    expectedException.expectMessage("It's not possible to disable the rule after getScenario() has been called.");
    activityScenarioRule.getScenario();
    activityScenarioRule.disable();
  }

  @Test
  public void whenDisabledCalledTwice_ShouldThrowDisableRuleException() {
    expectedException.expect(DisableRuleException.class);
    expectedException.expectMessage("Multiple calls to disable() is not allowed.");
    activityScenarioRule.disable();
    activityScenarioRule.disable();
  }

  private static Stage lastLifeCycleTransition(Activity activity) {
    return ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity);
  }
}
