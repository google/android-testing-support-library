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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.view.View;
import android.widget.TextView;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.internal.platform.io.PlatformTestStorage;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class NoMatchingViewExceptionHandlerTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Rule public ExpectedException expectedException = none();

  private NoMatchingViewExceptionHandler handler;
  private Matcher<View> viewMatcher;
  private AtomicInteger failureCount = new AtomicInteger();
  @Mock private PlatformTestStorage testStorage;

  @Before
  public void setUp() {
    handler = new NoMatchingViewExceptionHandler(testStorage, failureCount);
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
  public void handle_noMatchingViewException() {
    NoMatchingViewException noMatchingViewException =
        new NoMatchingViewException.Builder()
            .withViewMatcher(viewMatcher)
            .withRootView(
                new TextView(InstrumentationRegistry.getInstrumentation().getTargetContext()))
            .build();
    String viewHierarchyMsg =
        "\n\nView Hierarchy:\n"
            + "+>TextView{id=-1, visibility=VISIBLE, width=0, height=0, has-focus=false,"
            + " has-focusable=false, has-window-focus=false, is-clickable=false, is-enabled=true,"
            + " is-focused=false, is-focusable=false, is-layout-requested=true, is-selected=false,"
            + " layout-params=null, tag=null, root-is-layout-requested=true,"
            + " has-input-connection=false, x=0.0, y=0.0, text=, input-type=0, ime-target=false,"
            + " has-links=false} ";

    failureCount.incrementAndGet();
    NoMatchingViewException thrown =
        assertThrows(
            NoMatchingViewException.class,
            () -> {
              handler.handle(noMatchingViewException, viewMatcher);
            });

    assertThat(thrown)
        .hasMessageThat()
        .contains("No views in hierarchy found matching: A view matcher" + viewHierarchyMsg);
    verify(testStorage).addOutputFile(eq("view-hierarchy-1.txt"), eq(viewHierarchyMsg));
  }

  @Test
  public void handle_nonNoMatchingViewException() {
    // No-op. No exception should be thrown.
    handler.handle(new Throwable("A random error"), viewMatcher);
  }
}
