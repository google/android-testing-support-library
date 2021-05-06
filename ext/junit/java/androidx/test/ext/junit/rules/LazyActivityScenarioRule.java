package androidx.test.ext.junit.rules;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.EspressoException;

import org.junit.rules.ExternalResource;

import static androidx.test.internal.util.Checks.checkNotNull;

/**
 * Resembles {@link ActivityScenarioRule}, but instead of launching the Activity under test straight
 * away, {@link LazyActivityScenarioRule} defers this until the first call to
 * {@link LazyActivityScenarioRule#getScenario()}.
 * <p>This can be used to initialize different setups/options before the test launches.</p>
 *
 * <p>Example:
 *
 * <pre>
 *   &#64;Rule
 *   public LazyActivityScenarioRule<MyActivity> rule = new LazyActivityScenarioRule<>(MyActivity.class);
 *
 *   &#64;Test
 *   public void myTest() {
 *     // Do initialization of your test setup here
 *     // ie. populate an in-memory database, prepare test data, deactivate wifi etc.
 *
 *
 *     // Launch the activity under test
 *     ActivityScenario<MyActivity> scenario = rule.getScenario();
 *     // Your test code goes here.
 *   }
 * </pre>
 *
 * <p>You can also explicitly disable the rule inside a specific test.
 * <p>Example:
 *
 * <pre>
 *   &#64;Rule
 *   public LazyActivityScenarioRule<MyActivity> rule = new LazyActivityScenarioRule<>(MyActivity.class);
 *
 *   &#64;Test
 *   public void myTest() {
 *
 *     rule.disable() // This disables the rule for a specific test
 *     // Your test code goes here.
 *   }
 * </pre>
 *
 * @see ActivityScenarioRule
 */
public class LazyActivityScenarioRule<A extends Activity> extends ExternalResource {

    /**
     * Same as {@link java.util.function.Supplier} which requires API level 24.
     *
     * @hide
     */
    private interface Supplier<T> {
        T get();
    }

    @NonNull
    private final LazyActivityScenarioRule.Supplier<ActivityScenario<A>> scenarioSupplier;
    @Nullable
    private ActivityScenario<A> scenario;
    private boolean disableRule = false;

    /**
     * Constructs {@link LazyActivityScenarioRule} for a given activity class.
     *
     * @param activityClass an activity class to launch
     */
    public LazyActivityScenarioRule(Class<A> activityClass) {
        scenarioSupplier = () -> ActivityScenario.launch(checkNotNull(activityClass));
    }

    /**
     * @see #LazyActivityScenarioRule(Class)
     * @param activityOptions an activity options bundle to be passed along with the intent to start
     *                        activity.
     */
    public LazyActivityScenarioRule(Class<A> activityClass, @Nullable Bundle activityOptions) {
        scenarioSupplier = () -> ActivityScenario.launch(checkNotNull(activityClass), activityOptions);
    }

    /**
     * Constructs {@link LazyActivityScenarioRule} with a given intent.
     *
     * @param startActivityIntent an intent to start an activity
     */
    public LazyActivityScenarioRule(Intent startActivityIntent) {
        scenarioSupplier = () -> ActivityScenario.launch(checkNotNull(startActivityIntent));
    }

    /**
     * @see #LazyActivityScenarioRule(Intent)
     * @param activityOptions an activity options bundle to be passed along with the intent to start
     *                        activity.
     */
    public LazyActivityScenarioRule(Intent startActivityIntent, @Nullable Bundle activityOptions) {
        scenarioSupplier =
                () -> ActivityScenario.launch(checkNotNull(startActivityIntent), activityOptions);
    }

    @Override
    protected void after() {
        if (disableRule) return;
        if (scenario == null) {
            throw new NoActivityLaunchedException(
                    "No activities found. Did you remember to call getScenario() to " +
                            "launch the activity under test.");
        }
        scenario.close();
    }

    /**
     * Returns {@link ActivityScenario} of the given activity class.
     *
     * @throws DisableRuleException if you call this method after
     * a {@link LazyActivityScenarioRule#disable()} call
     * @throws NullPointerException if you call this method while test is not running
     * @return a non-null {@link ActivityScenario} instance
     */
    @NonNull
    public ActivityScenario<A> getScenario() {
        if (disableRule) {
            throw new DisableRuleException("Rule has been disabled. Calling getScenario() is not allowed.");
        }
        if (scenario == null) scenario = scenarioSupplier.get();
        return checkNotNull(scenario);
    }

    /**
     * If you have a test that doesn't need an Activity to run you can explicitly
     * disable {@link LazyActivityScenarioRule} during a test.
     *
     * <p>Example:
     *
     * <pre>
     *   &#64;Rule
     *   public LazyActivityScenarioRule<MyActivity> rule = new LazyActivityScenarioRule<>(MyActivity.class);
     *
     *   &#64;Test
     *   public void myTest() {
     *
     *     rule.disable() // This disables the rule for a specific test
     *     // Your test code goes here.
     *   }
     * </pre>
     *
     * @throws DisableRuleException if called multiple times or if called
     * after {@link LazyActivityScenarioRule#getScenario()}
     */
    public void disable() {
        if (scenario != null) {
            throw new DisableRuleException(
                    "It's not possible to disable the rule after getScenario() has been called.");
        }
        if (disableRule) {
            throw new DisableRuleException("Multiple calls to disable() is not allowed.");
        }
        disableRule = true;
    }

    /** An exception which indicates that no activity has been launched. */
    public static final class NoActivityLaunchedException extends IllegalStateException
            implements EspressoException {

        public NoActivityLaunchedException(String message) {
            super(message);
        }
    }

    /**
     * An exception which indicates an illegal state regarding the
     * use of {@link LazyActivityScenarioRule#disable()}.
     * */
    public static final class DisableRuleException extends IllegalStateException
            implements EspressoException {

        public DisableRuleException(String message) {
            super(message);
        }
    }
}
