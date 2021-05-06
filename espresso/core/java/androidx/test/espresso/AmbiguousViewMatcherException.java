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

package androidx.test.espresso;

import static com.google.common.base.Preconditions.checkNotNull;

import android.view.View;
import androidx.test.espresso.util.HumanReadables;
import androidx.test.internal.platform.util.TestOutputEmitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.Locale;
import org.hamcrest.Matcher;

/**
 * An exception which indicates that a Matcher<View> matched multiple views in the hierarchy when
 * only one view was expected. It should be called only from the main thread.
 *
 * <p>Contains details about the matcher and the current view hierarchy to aid in debugging.
 *
 * <p>Since this is usually an unrecoverable error this exception is a runtime exception.
 *
 * <p>References to the view and failing matcher are purposefully not included in the state of this
 * object - since it will most likely be created on the UI thread and thrown on the instrumentation
 * thread, it would be invalid to touch the view on the instrumentation thread. Also the view
 * hierarchy may have changed since exception creation (leading to more confusion).
 */
public final class AmbiguousViewMatcherException extends RuntimeException
    implements EspressoException {

  private Matcher<? super View> viewMatcher;
  private View rootView;
  private View view1;
  private View view2;
  private View[] others;

  private AmbiguousViewMatcherException(String description) {
    super(description);
    TestOutputEmitter.dumpThreadStates("ThreadState-AmbiguousViewMatcherException.txt");
  }

  private AmbiguousViewMatcherException(Builder builder) {
    super(getErrorMessage(builder));
    this.viewMatcher = builder.viewMatcher;
    this.rootView = builder.rootView;
    this.view1 = builder.view1;
    this.view2 = builder.view2;
    this.others = builder.others;
  }

  private static String getErrorMessage(Builder builder) {
    String errorMessage = "";
    // if (builder.includeViewHierarchy) {
    ImmutableSet<View> ambiguousViews =
        ImmutableSet.<View>builder().add(builder.view1, builder.view2).add(builder.others).build();
    for (View v : Lists.newArrayList(ambiguousViews)) {
      System.out.println("~~~~~~" + v.toString());
    }
      errorMessage =
          HumanReadables.getViewHierarchyErrorMessage(
              builder.rootView,
              Lists.newArrayList(ambiguousViews),
              String.format(
                  Locale.ROOT,
                  "'%s' matches multiple views in the hierarchy.",
                  builder.viewMatcher),
              "****MATCHES****");
    // } else {
    //   errorMessage =
    //       String.format(
    //           Locale.ROOT, "Multiple Ambiguous Views found for matcher %s", builder.viewMatcher);
    // }
    return errorMessage;
  }

  /** Builder for {@link AmbiguousViewMatcherException}. */
  public static class Builder {
    private Matcher<? super View> viewMatcher;
    private View rootView;
    private View view1;
    private View view2;
    private View[] others;
    private boolean includeViewHierarchy = true;

    public Builder from(AmbiguousViewMatcherException exception) {
      this.viewMatcher = exception.viewMatcher;
      this.rootView = exception.rootView;
      this.view1 = exception.view1;
      this.view2 = exception.view2;
      this.others = exception.others;
      return this;
    }

    public Builder withViewMatcher(Matcher<? super View> viewMatcher) {
      this.viewMatcher = viewMatcher;
      return this;
    }

    public Builder withRootView(View rootView) {
      this.rootView = rootView;
      return this;
    }

    public Builder withView1(View view1) {
      this.view1 = view1;
      return this;
    }

    public Builder withView2(View view2) {
      this.view2 = view2;
      return this;
    }

    public Builder withOtherAmbiguousViews(View... others) {
      this.others = others;
      return this;
    }

    public Builder includeViewHierarchy(boolean includeViewHierarchy) {
      this.includeViewHierarchy = includeViewHierarchy;
      return this;
    }

    public AmbiguousViewMatcherException build() {
      checkNotNull(viewMatcher);
      checkNotNull(rootView);
      checkNotNull(view1);
      checkNotNull(view2);
      checkNotNull(others);
      return new AmbiguousViewMatcherException(this);
    }
  }
}
