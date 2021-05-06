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
import androidx.test.espresso.util.EspressoOptional;
import androidx.test.espresso.util.HumanReadables;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Locale;
import org.hamcrest.Matcher;

/**
 * Indicates that a given matcher did not match any elements in the view hierarchy.
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
public final class NoMatchingViewException extends RuntimeException implements EspressoException {

  private Matcher<? super View> viewMatcher;
  private View rootView;
  private List<View> adapterViews = Lists.newArrayList();
  private boolean includeViewHierarchy = true;
  private EspressoOptional<String> adapterViewWarning = EspressoOptional.<String>absent();

  private NoMatchingViewException(String description) {
    super(description);
  }

  private NoMatchingViewException(Builder builder) {
    super(getErrorMessage(builder), builder.cause);
    this.viewMatcher = builder.viewMatcher;
    this.rootView = builder.rootView;
    this.adapterViews = builder.adapterViews;
    this.adapterViewWarning = builder.adapterViewWarning;
    this.includeViewHierarchy = builder.includeViewHierarchy;
  }

  /**
   * Returns a string description of the ViewMatcher that did not match any view in the hierarchy.
   */
  public String getViewMatcherDescription() {
    String viewMatcherDescription = "unknown";
    if (null != viewMatcher) {
      viewMatcherDescription = viewMatcher.toString();
    }
    return viewMatcherDescription;
  }

  public View getRootView() {
    return rootView;
  }

  private static String getErrorMessage(Builder builder) {
    String errorMessage = "";
    if (builder.includeViewHierarchy) {
      String message =
          String.format(
              Locale.ROOT, "No views in hierarchy found matching: %s", builder.viewMatcher);
      if (builder.adapterViewWarning.isPresent()) {
        message = message + builder.adapterViewWarning.get();
      }
      errorMessage =
          HumanReadables.getViewHierarchyErrorMessage(
              builder.rootView, null /* problemViews */, message, null /* problemViewSuffix */);
    } else {
      errorMessage =
          String.format(Locale.ROOT, "Could not find a view that matches %s", builder.viewMatcher);
    }
    return errorMessage;
  }

  /** Builder for {@link NoMatchingViewException}. */
  public static class Builder {

    private Matcher<? super View> viewMatcher;
    private View rootView;
    private List<View> adapterViews = Lists.newArrayList();
    private boolean includeViewHierarchy = true;
    private EspressoOptional<String> adapterViewWarning = EspressoOptional.<String>absent();
    private Throwable cause;

    public Builder from(NoMatchingViewException exception) {
      this.viewMatcher = exception.viewMatcher;
      this.rootView = exception.rootView;
      this.adapterViews = exception.adapterViews;
      this.adapterViewWarning = exception.adapterViewWarning;
      this.includeViewHierarchy = exception.includeViewHierarchy;
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

    public Builder withAdapterViews(List<View> adapterViews) {
      this.adapterViews = adapterViews;
      return this;
    }

    public Builder includeViewHierarchy(boolean includeViewHierarchy) {
      this.includeViewHierarchy = includeViewHierarchy;
      return this;
    }

    public Builder withAdapterViewWarning(EspressoOptional<String> adapterViewWarning) {
      this.adapterViewWarning = adapterViewWarning;
      return this;
    }

    public Builder withCause(Throwable cause) {
      this.cause = cause;
      return this;
    }

    public NoMatchingViewException build() {
      checkNotNull(viewMatcher);
      checkNotNull(rootView);
      checkNotNull(adapterViews);
      checkNotNull(adapterViewWarning);
      return new NoMatchingViewException(this);
    }
  }
}
