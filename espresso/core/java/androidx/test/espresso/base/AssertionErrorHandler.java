/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.view.View;
import androidx.test.espresso.base.DefaultFailureHandler.TypedFailureHandler;
import junit.framework.AssertionFailedError;
import org.hamcrest.Matcher;

/**
 * An Espresso failure handler that handles an {@link AssertionError}.
 */
class AssertionErrorHandler extends TypedFailureHandler<Throwable> {

  public AssertionErrorHandler(Class<?>... expectedTypes) {
    super(expectedTypes);
  }

  @Override
  public void handleSafely(Throwable error, Matcher<View> viewMatcher) {
    Error newError = new AssertionFailedWithCauseError(error.getMessage(), error);
    newError.setStackTrace(Thread.currentThread().getStackTrace());
    throw newError;
  }

  static final class AssertionFailedWithCauseError extends AssertionFailedError {
    /* junit hides the cause constructor. */
    public AssertionFailedWithCauseError(String message, Throwable cause) {
      super(message);
      initCause(cause);
    }
  }
}
