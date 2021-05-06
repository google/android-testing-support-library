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

import static org.junit.rules.ExpectedException.none;

import android.view.View;
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
public class ThrowableHandlerTest {

  @Rule public ExpectedException expectedException = none();

  private ThrowableHandler handler;
  private Matcher<View> viewMatcher;

  @Before
  public void setUp() {
    handler = new ThrowableHandler();
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
  public void handle_checkedException() {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("A null pointer exception");
    handler.handle(new NullPointerException("A null pointer exception"), viewMatcher);
  }

  @Test
  public void handle_runtimeException() {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("A runtime error");
    handler.handle(new RuntimeException("A runtime error"), viewMatcher);
  }
}
