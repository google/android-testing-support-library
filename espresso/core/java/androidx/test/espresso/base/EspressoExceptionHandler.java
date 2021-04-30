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

import static com.google.common.base.Throwables.throwIfUnchecked;

import android.view.View;
import androidx.test.espresso.EspressoException;
import androidx.test.espresso.FailureHandler;
import org.hamcrest.Matcher;

/** An Espresso failure handler that handles an {@link EspressoException}. */
class EspressoExceptionHandler implements FailureHandler {

  @Override
  public void handle(Throwable error, Matcher<View> viewMatcher) {
    if (!(error instanceof EspressoException)) {
      return;
    }

    error.setStackTrace(Thread.currentThread().getStackTrace());
    throwIfUnchecked(error);
    throw new RuntimeException(error);
  }
}
