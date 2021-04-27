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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.rules.ExpectedException.none;

import android.view.View;
import androidx.test.espresso.base.AssertionErrorHandler.AssertionFailedWithCauseError;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AssertionErrorHandlerTest {

  @Rule public ExpectedException expectedException = none();

  private AssertionErrorHandler handler;
  private Matcher<View> viewMatcher;

  @Before
  public void setUp() {
    handler = new AssertionErrorHandler(AssertionError.class);
    viewMatcher =
        new BaseMatcher<View>() {
          @Override
          public boolean matches(Object o) {
            return false;
          }

          @Override
          public void describeTo(Description description) {
            description.appendText("A view matcher");
          }
        };
  }

  @Test
  public void handle_assertionError() {
    AssertionFailedWithCauseError thrown =
        assertThrows(
            AssertionFailedWithCauseError.class,
            () -> handler.handle(new AssertionError("An assertion error"), viewMatcher));
    assertThat(thrown).hasMessageThat().contains("An assertion error");
  }

  @Test
  public void handle_nonAssertionError() {
    // No-op. No exception should be thrown.
    handler.handle(new RuntimeException("A runtime error"), viewMatcher);
  }
}
