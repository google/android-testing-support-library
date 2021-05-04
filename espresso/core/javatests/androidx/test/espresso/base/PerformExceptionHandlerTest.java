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
import androidx.test.espresso.PerformException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PerformExceptionHandlerTest {

  @Rule public ExpectedException expectedException = none();

  private PerformExceptionHandler handler;
  private Matcher<View> viewMatcher;

  @Before
  public void setUp() {
    handler =
        new PerformExceptionHandler(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            PerformException.class);
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
  public void handle_performException() {
    PerformException performException =
        new PerformException.Builder()
            .withActionDescription("a click")
            .withViewDescription("a text view")
            .build();

    PerformException thrown =
        assertThrows(PerformException.class, () -> handler.handle(performException, viewMatcher));
    assertThat(thrown).hasMessageThat().contains("A view matcher");
  }

  @Test
  public void handle_nonPerformException() {
    // No-op. No exception should be thrown.
    handler.handle(new Throwable("A non-perform error"), viewMatcher);
  }
}
