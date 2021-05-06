/*
 * Copyright (C) 2015 The Android Open Source Project
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

package androidx.test.espresso.web.sugar;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.isJavascriptEnabled;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.Matchers.any;

import androidx.annotation.CheckResult;
import android.view.View;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.remote.annotation.RemoteMsgConstructor;
import androidx.test.espresso.remote.annotation.RemoteMsgField;
import androidx.test.espresso.web.action.AtomAction;
import androidx.test.espresso.web.action.EnableJavascriptAction;
import androidx.test.espresso.web.assertion.WebAssertion;
import androidx.test.espresso.web.model.Atom;
import androidx.test.espresso.web.model.ElementReference;
import androidx.test.espresso.web.model.WindowReference;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry.AxtVersions;
import androidx.test.internal.platform.util.TestOutputEmitter;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import org.hamcrest.Matcher;

/**
 * An Entry Point to work with WebViews on Android.
 *
 * <p>Similar to onData, WebView interactions are actually composed of several ViewActions. However
 * they need to be properly orchestrated and are quite verbose. Web and WebInteraction wrap this
 * boilerplate and give an Espresso like feel to interacting with WebViews.
 *
 * <p>WebView interactions constantly cross the Java/Javascript boundary to do their work, since
 * there is no chance of introducing race conditions by exposing data from the Javascript
 * environment (everything we see on the Java side is an isolated copy), returning data from
 * WebInteractions is fully supported.
 */
public final class Web {
  static {
    UsageTrackerRegistry.getInstance().trackUsage("Espresso-Web", AxtVersions.ESPRESSO_VERSION);
    // Also adds the usage data as test output properties. By default it's no-op.
    Map<String, Serializable> usageProperties = new HashMap<>();
    usageProperties.put("Espresso-Web", AxtVersions.ESPRESSO_VERSION);
    TestOutputEmitter.addOutputProperties(usageProperties);
  }

  public static WebInteraction<Void> onWebView() {
    return onWebView(isJavascriptEnabled());
  }

  public static WebInteraction<Void> onWebView(Matcher<View> viewMatcher) {
    return new WebInteraction<Void>(viewMatcher);
  }

  private static class Timeout {
    private final long timeout;
    private final TimeUnit unit;
    static final Timeout NONE = new Timeout(-1, TimeUnit.MILLISECONDS, false);

    private Timeout(long timeout, TimeUnit unit, boolean check) {
      this.timeout = timeout;
      this.unit = unit;
      if (check) {
        checkArgument(timeout > 0);
        checkNotNull(unit);
      }
    }
  }

  /**
   * Analogous to a ViewInteraction or a DataInteraction, a WebInteraction exposes a fluent API to
   * the underlying WebView.
   */
  public static class WebInteraction<R> {
    private final Matcher<View> viewMatcher;
    private final boolean brandNew;

    @Nullable private final R result;
    @Nullable private final WindowReference window;
    @Nullable private final ElementReference element;
    private final Timeout timeout;

    private WebInteraction(Matcher<View> viewMatcher) {
      this(viewMatcher, null, null, null, true, new Timeout(10, TimeUnit.SECONDS, true));
    }

    private WebInteraction(
        Matcher<View> viewMatcher,
        R result,
        WindowReference window,
        ElementReference element,
        boolean brandNew,
        Timeout timeout) {
      this.viewMatcher = checkNotNull(viewMatcher);
      this.result = result;
      this.window = window;
      this.element = element;
      this.brandNew = brandNew;
      this.timeout = timeout;
    }

    /**
     * Removes the Element and Window references from this interaction.
     *
     * <p>This is usually necessary when a prior action (for example a click) introduces a
     * navigation that invalidates the ElementReference and WindowReference pointers.
     */
    public WebInteraction<R> reset() {
      return new WebInteraction<R>(viewMatcher, result, null, null, brandNew, timeout);
    }

    /**
     * Performs a force enable of Javascript on a WebView.
     *
     * <p>All WebView interactions are done via Javascript - therefore the WebView we are working on
     * must support Javascript evaluation.
     *
     * <p>Enabling Javascript may cause the WebView under test to be reloaded. This is necessary to
     * ensure the test infrastructure javascript bridges are loaded by the WebView.
     */
    public WebInteraction<R> forceJavascriptEnabled() {
      onView(viewMatcher).perform(new EnableJavascriptAction());
      return this;
    }

    /**
     * Disables all Timeouts on this WebInteraction.
     *
     * <p>Javascript evaluation is performed asynchronously on the WebKit/Chromium thread. By
     * default we wait a short while for the result to be delivered back to the test.
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> withNoTimeout() {
      return new WebInteraction<R>(viewMatcher, result, window, element, brandNew, Timeout.NONE);
    }

    /** Sets a specific timeout for this WebInteraction. */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> withTimeout(long amount, TimeUnit unit) {
      return new WebInteraction<R>(
          viewMatcher, result, window, element, brandNew, new Timeout(amount, unit, true));
    }

    /**
     * Causes this WebInteraction to have it's javascript evaluated in a particular DOM window.
     *
     * <p>By default Javascript may be evaluated in the main window. However in an application which
     * uses frames, you may want to evaluate in another frame.
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> inWindow(WindowReference window) {
      return new WebInteraction<R>(viewMatcher, result, window, element, brandNew, timeout);
    }

    /**
     * Causes this WebInteraction to have it's javascript evaluated in a particular DOM window.
     *
     * <p>This method accepts an Atom which will be evaluated in the main window to choose a
     * particular DOM window for further interactions. This method will block until the the provided
     * Atom returns with a result.
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> inWindow(Atom<WindowReference> windowPicker) {
      return new WebInteraction<R>(
          viewMatcher, result, doEval(windowPicker, null, null), element, brandNew, timeout);
    }

    /**
     * Causes this WebInteraction to supply the given ElementReference to the Atom prior to
     * evaluation.
     *
     * <p>Calling this method resets any previously selected ElementReference.
     *
     * <p>{@see Atom#getArguments}
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> withElement(ElementReference element) {
      return new WebInteraction<R>(viewMatcher, result, window, element, brandNew, timeout);
    }

    /**
     * Causes this WebInteraction to supply the given ElementReference to the Atom prior to
     * evaluation.
     *
     * <p>{@see Atom#getArguments}
     *
     * <p>This method accepts an Atom<ElementReference> which it will evaluate on the current
     * context's Window. This method blocks until the evaluation completes.
     *
     * <p>Calling this method resets any previously selected ElementReference.
     *
     * <p>If you want to evaluate the elementPicker in the context of the previously selected
     * ElementReference {@see #withContextualElement}
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> withElement(Atom<ElementReference> elementPicker) {
      return new WebInteraction<R>(
          viewMatcher, result, window, doEval(elementPicker, window, null), brandNew, timeout);
    }

    /**
     * Allows for contextually evaluating this WebInteraction with the selected element.
     *
     * <p>Specifically the elementPicker atom will be evaluated with the CURRENTLY selected element
     * to discover the new element to work against.
     *
     * <p>This allows callers to interact with a document that looks like this:
     *
     * <pre>
     * {@literal
     * <div id="teacher">
     *   <div id="person_name">
     *     <p>Socrates</p>
     *   </div>
     * </div>
     * <div id="student">
     *   <div id="person_name">
     *     <p>Plato</p>
     *   </div>
     * </div>
     * }
     * </pre>
     *
     * With code like this:
     *
     * <pre>{@code
     * onWebView()
     *   .withElement(findElement(Locator.ID, "teacher"))
     *   .withContextualElement(findElement(Locator.ID, "person_name"))
     *   .check(webMatches(getText(), containsString("Socrates")));
     * }</pre>
     */
    @CheckResult
    @CheckReturnValue
    public WebInteraction<R> withContextualElement(Atom<ElementReference> elementPicker) {
      return new WebInteraction<R>(
          viewMatcher, result, window, doEval(elementPicker, window, element), brandNew, timeout);
    }

    /**
     * Executes the provided atom within the current context (the combination of Window and Element
     * References).
     *
     * <p>This method blocks until the Atom returns. The result of the Atom's evaluation is used to
     * create a new instance of WebInteraction which can be used to access the result of the Atom's
     * evaluation.
     */
    public <E> WebInteraction<E> perform(Atom<E> atom) {
      E newResult = doEval(atom, window, element);
      return new WebInteraction<E>(viewMatcher, newResult, window, element, false, timeout);
    }

    /**
     * Evaluates the given WebAssertion.
     *
     * <p>The WebAssertion's atom is evaluated, after it's evaluation completes, the WebAssertion is
     * run on the main thread to perform further checks. The WebAssertion is given the Atom's result
     * and the WebView it had run against.
     *
     * <p>After this method completes, the result of the atom's evaluation is avaliable via get.
     */
    public <E> WebInteraction<E> check(WebAssertion<E> assertion) {
      E newResult = doEval(assertion.getAtom(), window, element);
      onView(viewMatcher).check(assertion.toViewAssertion(newResult));
      return new WebInteraction<E>(viewMatcher, newResult, window, element, false, timeout);
    }

    private <E> E doEval(Atom<E> atom, WindowReference window, ElementReference elem) {
      checkNotNull(atom, "Need an atom!");

      AtomAction<E> atomAction = new AtomAction(atom, window, elem);
      onView(viewMatcher).perform(atomAction);
      try {
        if (timeout == Timeout.NONE) {
          return atomAction.get();
        } else {
          return atomAction.get(timeout.timeout, timeout.unit);
        }
      } catch (ExecutionException ee) {
        onView(viewMatcher).perform(new ExceptionPropagator(ee.getCause()));
        return null; // always throws.
      } catch (InterruptedException ie) {
        onView(viewMatcher).perform(new ExceptionPropagator(ie));
        return null; // always throws.
      } catch (TimeoutException te) {
        onView(viewMatcher).perform(new ExceptionPropagator(te));
        return null; // always throws.
      } catch (RuntimeException re) {
        onView(viewMatcher).perform(new ExceptionPropagator(re));
        return null; // always throws.
      }
    }

    /** Returns the result of a prior call to perform or check. */
    public R get() {
      checkState(!brandNew, "Perform or Check never called on this WebInteraction!");
      return result;
    }

    static class ExceptionPropagator implements ViewAction {
      @RemoteMsgField(order = 0)
      private final RuntimeException error;

      @RemoteMsgConstructor
      public ExceptionPropagator(RuntimeException error) {
        this.error = checkNotNull(error);
      }

      public ExceptionPropagator(Throwable t) {
        this(new RuntimeException(t));
      }

      @Override
      public String getDescription() {
        return "Propagate: " + error;
      }

      @Override
      public void perform(UiController uiController, View view) {
        throw error;
      }

      @SuppressWarnings("unchecked")
      @Override
      public Matcher<View> getConstraints() {
        return any(View.class);
      }
    }
  }
}
