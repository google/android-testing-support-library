/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.espresso.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.throwIfUnchecked;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import androidx.test.espresso.EspressoException;
import androidx.test.espresso.FailureHandler;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.internal.inject.TargetContext;
import androidx.test.internal.platform.util.TestOutputEmitter;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import junit.framework.AssertionFailedError;
import org.hamcrest.Matcher;

/**
 * Espresso's default {@link FailureHandler}. If this does not fit your needs, feel free to provide
 * your own implementation via Espresso.setFailureHandler(FailureHandler).
 */
public final class DefaultFailureHandler implements FailureHandler {

  private static final AtomicInteger failureCount = new AtomicInteger(0);
  private final Context appContext;

  @Inject
  public DefaultFailureHandler(@TargetContext Context appContext) {
    this.appContext = checkNotNull(appContext);
  }

  @Override
  public void handle(Throwable error, Matcher<View> viewMatcher) {
    int count = failureCount.incrementAndGet();
    TestOutputEmitter.takeScreenshot("view-op-error-" + count + ".png");
    TestOutputEmitter.captureWindowHierarchy("explore-window-hierarchy-" + count + ".xml");
    if (error instanceof EspressoException
        || error instanceof AssertionFailedError
        || error instanceof AssertionError) {
      throwIfUnchecked(getUserFriendlyError(error, viewMatcher));
      throw new RuntimeException(getUserFriendlyError(error, viewMatcher));
    } else {
      throwIfUnchecked(error);
      throw new RuntimeException(error);
    }
  }

  /**
   * When the error is coming from espresso, it is more user friendly to: 1. propagate assertions as
   * assertions 2. swap the stack trace of the error to that of current thread (which will show
   * directly where the actual problem is)
   */
  private Throwable getUserFriendlyError(Throwable error, Matcher<View> viewMatcher) {
    if (error instanceof PerformException) {
      StringBuilder sb = new StringBuilder();
      if (!isAnimationAndTransitionDisabled(appContext)) {
        sb.append(
            "Animations or transitions are enabled on the target device.\n"
                + "For more info check:"
                + " https://developer.android.com/training/testing/espresso/setup#set-up-environment\n\n");
      }
      sb.append(viewMatcher.toString());
      // Re-throw the exception with the viewMatcher (used to locate the view) as the view
      // description (makes the error more readable). The reason we do this here: not all creators
      // of PerformException have access to the viewMatcher.
      throw new PerformException.Builder()
          .from((PerformException) error)
          .withViewDescription(sb.toString())
          .build();
    }

    if (error instanceof AssertionError) {
      // reports Failure instead of Error.
      // assertThat(...) throws an AssertionFailedError.
      error = new AssertionFailedWithCauseError(error.getMessage(), error);
    }

    error.setStackTrace(Thread.currentThread().getStackTrace());
    return error;
  }

  private static final class AssertionFailedWithCauseError extends AssertionFailedError {
    /* junit hides the cause constructor. */
    public AssertionFailedWithCauseError(String message, Throwable cause) {
      super(message);
      initCause(cause);
    }
  }

  /**
   * Checks whether animations and transitions are disabled on the current device.
   *
   * @param context The target's context.
   * @return <code>true</code> if animations or transitions are disabled, else <code>false</code>.
   */
  private static boolean isAnimationAndTransitionDisabled(Context context) {
    ContentResolver resolver = context.getContentResolver();
    boolean isTransitionAnimationDisabled = isEqualToZero(getTransitionAnimationScale(resolver));
    boolean isWindowAnimationDisabled = isEqualToZero(getWindowAnimationScale(resolver));
    boolean isAnimatorDisabled = isEqualToZero(getAnimatorDurationScale(resolver));

    return isTransitionAnimationDisabled && isWindowAnimationDisabled && isAnimatorDisabled;
  }

  private static boolean isEqualToZero(float value) {
    return Float.compare(Math.abs(value), 0.0f) == 0;
  }

  private static float getTransitionAnimationScale(ContentResolver resolver) {
    return getSetting(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.System.TRANSITION_ANIMATION_SCALE);
  }

  private static float getWindowAnimationScale(ContentResolver resolver) {
    return getSetting(
        resolver, Settings.Global.WINDOW_ANIMATION_SCALE, Settings.System.WINDOW_ANIMATION_SCALE);
  }

  private static float getAnimatorDurationScale(ContentResolver resolver) {
    if (isJellyBeanMR1OrHigher()) {
      return getSetting(
          resolver,
          Settings.Global.ANIMATOR_DURATION_SCALE,
          Settings.System.ANIMATOR_DURATION_SCALE);
    }
    return 0f;
  }

  /**
   * Compatibility wrapper for obtaining animation related settings.
   *
   * <p>Gets an animation specific setting regardless of the API level the tests are running on.
   *
   * @param resolver The content resolver to use.
   * @param current The setting name to use on {@link JELLY_BEAN_MR1} and above.
   * @see #getGlobalSetting(ContentResolver, String)
   * @param deprecated The setting name to use up to {@link JELLY_BEAN_MR1}.
   * @see #getSystemSetting(ContentResolver, String)
   */
  private static float getSetting(ContentResolver resolver, String current, String deprecated) {
    if (isJellyBeanMR1OrHigher()) {
      return getGlobalSetting(resolver, current);
    } else {
      return getSystemSetting(resolver, deprecated);
    }
  }

  /** Helper method to determine if API level is JellyBean MR1 or higher. */
  private static boolean isJellyBeanMR1OrHigher() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;
  }

  /**
   * Method to get global settings, which are available from {@link JELLY_BEAN_MR1} onwards.
   *
   * @param resolver The target context's content resolver.
   * @param setting The global setting to look for.
   * @return The setting's value or <code>0f</code> if none was found.
   */
  private static float getGlobalSetting(ContentResolver resolver, String setting) {
    try {
      return Settings.Global.getFloat(resolver, setting);
    } catch (Settings.SettingNotFoundException e) {
      return 0f;
    }
  }

  /**
   * Method to get system settings, which hold desired values until {@link JELLY_BEAN_MR1}.
   *
   * @param resolver The target context's content resolver.
   * @param setting The system setting to look for.
   * @return The setting's value or <code>0f</code> if none was found.
   */
  private static float getSystemSetting(ContentResolver resolver, String setting) {
    try {
      return Settings.System.getFloat(resolver, setting);
    } catch (Settings.SettingNotFoundException e) {
      return 0f;
    }
  }
}
