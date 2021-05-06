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
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.util.HumanReadables;
import androidx.test.internal.platform.io.PlatformTestStorage;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matcher;

/** An Espresso failure handler that handles an {@link NoMatchingViewException}. */
class NoMatchingViewExceptionHandler extends EspressoExceptionHandler {
  private final PlatformTestStorage testStorage;
  private final AtomicInteger failureCount;

  public NoMatchingViewExceptionHandler(
      PlatformTestStorage testStorage, AtomicInteger failureCount) {
    this.testStorage = testStorage;
    this.failureCount = failureCount;
  }

  @Override
  public void handle(Throwable error, Matcher<View> viewMatcher) {
    if (!(error instanceof NoMatchingViewException)) {
      return;
    }

    NoMatchingViewException noMatchingViewException = (NoMatchingViewException) error;
    String viewHierarchyMsg =
        HumanReadables.getViewHierarchyErrorMessage(
            noMatchingViewException.getRootView(), null, "", null);
    testStorage.addOutputFile("view-hierarchy-" + failureCount + ".txt", viewHierarchyMsg);

    super.handle(error, viewMatcher);
  }
}
