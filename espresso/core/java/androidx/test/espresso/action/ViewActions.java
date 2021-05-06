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

package androidx.test.espresso.action;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.is;

import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewAssertion;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.annotation.Nonnull;
import org.hamcrest.Matcher;

/** A collection of common {@link ViewActions}. */
public final class ViewActions {

  private ViewActions() {}

  /**
   * The distance of a swipe's start position from the view's edge, in terms of the view's length.
   * We do not start the swipe exactly on the view's edge, but somewhat more inward, since swiping
   * from the exact edge may behave in an unexpected way (e.g. may open a navigation drawer).
   */
  private static final float EDGE_FUZZ_FACTOR = 0.083f;

  /** A set of {@code ViewAssertion}s to be executed before the ViewActions in this class. */
  private static Set<Pair<String, ViewAssertion>> globalAssertions =
      new CopyOnWriteArraySet<Pair<String, ViewAssertion>>();

  /**
   * Adds a {@code ViewAssertion} to be run every time a {@code ViewAction} in this class is
   * performed. The assertion will be run prior to performing the action.
   *
   * @param name a name of the assertion to be added
   * @param viewAssertion a {@code ViewAssertion} to be added
   * @throws IllegalArgumentException if the name/viewAssertion pair is already contained in the
   *     global assertions.
   */
  public static void addGlobalAssertion(String name, ViewAssertion viewAssertion) {
    checkNotNull(name);
    checkNotNull(viewAssertion);
    Pair<String, ViewAssertion> vaPair = new Pair<String, ViewAssertion>(name, viewAssertion);
    checkArgument(
        !globalAssertions.contains(vaPair),
        "ViewAssertion with name %s is already in the global assertions!",
        name);
    globalAssertions.add(vaPair);
  }

  /**
   * Removes the given assertion from the set of assertions to be run before actions are performed.
   *
   * @param viewAssertion the assertion to remove
   * @throws IllegalArgumentException if the name/viewAssertion pair is not already contained in the
   *     global assertions.
   */
  public static void removeGlobalAssertion(ViewAssertion viewAssertion) {
    boolean removed = false;
    for (Pair<String, ViewAssertion> vaPair : globalAssertions) {
      if (viewAssertion != null && viewAssertion.equals(vaPair.second)) {
        removed = removed || globalAssertions.remove(vaPair);
      }
    }
    checkArgument(removed, "ViewAssertion was not in global assertions!");
  }

  public static void clearGlobalAssertions() {
    globalAssertions.clear();
  }

  /**
   * Performs all assertions before the {@code ViewAction}s in this class and then performs the
   * given {@code ViewAction}
   *
   * @param viewAction the {@code ViewAction} to perform after the assertions
   */
  public static ViewAction actionWithAssertions(final ViewAction viewAction) {
    if (globalAssertions.isEmpty()) {
      return viewAction;
    }
    return new ViewAction() {
      @Override
      public String getDescription() {
        StringBuilder msg = new StringBuilder("Running view assertions[");
        for (Pair<String, ViewAssertion> vaPair : globalAssertions) {
          msg.append(vaPair.first);
          msg.append(", ");
        }
        msg.append("] and then running: ");
        msg.append(viewAction.getDescription());
        return msg.toString();
      }

      @Override
      public Matcher<View> getConstraints() {
        return viewAction.getConstraints();
      }

      @Override
      public void perform(UiController uic, View view) {
        for (Pair<String, ViewAssertion> vaPair : globalAssertions) {
          Log.i("ViewAssertion", "Asserting " + vaPair.first);
          vaPair.second.check(view, null);
        }
        viewAction.perform(uic, view);
      }
    };
  }

  /**
   * Returns an action that clears text on the view.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction clearText() {
    return actionWithAssertions(new ReplaceTextAction(""));
  }

  /**
   * Returns an action that clicks the view for a specific input device and button state.
   *
   * <p><b>Note:</b> Not supported by API < 14. An {@link UnsupportedOperationException} will be
   * thrown if called on API < 14. For API < 14, call {@link #click()} instead.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   *
   * @param inputDevice source input device of the click. Example: {@link InputDevice#SOURCE_MOUSE}
   * @param buttonState buttonState associated with the click. Example: {@link
   *     MotionEvent#BUTTON_PRIMARY}
   */
  public static ViewAction click(int inputDevice, int buttonState) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      throw new UnsupportedOperationException();
    }
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.SINGLE, GeneralLocation.VISIBLE_CENTER, Press.FINGER, inputDevice, buttonState));
  }

  /**
   * Same as {@code click(int inputDevice, int buttonState)}, but uses {@link
   * InputDevice#SOURCE_UNKNOWN} as the inputDevice and {@link MotionEvent#BUTTON_PRIMARY} as the
   * buttonState.
   */
  public static ViewAction click() {
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.SINGLE,
            GeneralLocation.VISIBLE_CENTER,
            Press.FINGER,
            InputDevice.SOURCE_UNKNOWN,
            MotionEvent.BUTTON_PRIMARY));
  }

  /** Same as {@code click()}, but clicks in the top right. */
  public static ViewAction clickTopRight() {
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.SINGLE,
            GeneralLocation.TOP_RIGHT,
            Press.FINGER,
            InputDevice.SOURCE_UNKNOWN,
            MotionEvent.BUTTON_PRIMARY));
  }

  /**
   * Returns an action that performs a single click on the view.
   *
   * <p>If the click takes longer than the 'long press' duration (which is possible) the provided
   * rollback action is invoked on the view and a click is attempted again.
   *
   * <p>This is only necessary if the view being clicked on has some different behaviour for long
   * press versus a normal tap.
   *
   * <p>For example - if a long press on a particular view element opens a popup menu -
   * ViewActions.pressBack() may be an acceptable rollback action. <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *   <li>any constraints of the rollbackAction
   *       <ul>
   */
  public static ViewAction click(ViewAction rollbackAction) {
    checkNotNull(rollbackAction);
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.SINGLE,
            GeneralLocation.CENTER,
            Press.FINGER,
            InputDevice.SOURCE_UNKNOWN,
            MotionEvent.BUTTON_PRIMARY,
            rollbackAction));
  }

  /**
   * Returns an action that performs a swipe right-to-left across the vertical center of the view.
   * The swipe doesn't start at the very edge of the view, but is a bit offset.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction swipeLeft() {
    return actionWithAssertions(
        new GeneralSwipeAction(
            Swipe.FAST,
            GeneralLocation.translate(GeneralLocation.CENTER_RIGHT, -EDGE_FUZZ_FACTOR, 0),
            GeneralLocation.CENTER_LEFT,
            Press.FINGER));
  }

  /**
   * Returns an action that performs a swipe left-to-right across the vertical center of the view.
   * The swipe doesn't start at the very edge of the view, but is a bit offset.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction swipeRight() {
    return actionWithAssertions(
        new GeneralSwipeAction(
            Swipe.FAST,
            GeneralLocation.translate(GeneralLocation.CENTER_LEFT, EDGE_FUZZ_FACTOR, 0),
            GeneralLocation.CENTER_RIGHT,
            Press.FINGER));
  }

  /**
   * Returns an action that performs a swipe top-to-bottom across the horizontal center of the view.
   * The swipe doesn't start at the very edge of the view, but has a bit of offset.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction swipeDown() {
    return actionWithAssertions(
        new GeneralSwipeAction(
            Swipe.FAST,
            GeneralLocation.translate(GeneralLocation.TOP_CENTER, 0, EDGE_FUZZ_FACTOR),
            GeneralLocation.BOTTOM_CENTER,
            Press.FINGER));
  }

  /**
   * Returns an action that performs a swipe bottom-to-top across the horizontal center of the view.
   * The swipe doesn't start at the very edge of the view, but has a bit of offset.<br>
   * <br>
   * View constraints:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction swipeUp() {
    return actionWithAssertions(
        new GeneralSwipeAction(
            Swipe.FAST,
            GeneralLocation.translate(GeneralLocation.BOTTOM_CENTER, 0, -EDGE_FUZZ_FACTOR),
            GeneralLocation.TOP_CENTER,
            Press.FINGER));
  }

  /**
   * Returns an action that closes soft keyboard. If the keyboard is already closed, it is a no-op.
   */
  public static ViewAction closeSoftKeyboard() {
    return actionWithAssertions(new CloseKeyboardAction());
  }

  /**
   * Returns an action that presses the current action button (next, done, search, etc) on the IME
   * (Input Method Editor). The selected view will have its onEditorAction method called.
   */
  public static ViewAction pressImeActionButton() {
    return actionWithAssertions(new EditorAction());
  }

  /**
   * Returns an action that clicks the back button.
   *
   * @throws PerformException if Espresso navigates outside the application or process under test.
   */
  public static ViewAction pressBack() {
    return actionWithAssertions(new PressBackAction(true));
  }

  /**
   * Similar to {@link #pressBack()} but will <b>not</b> throw an exception when Espresso navigates
   * outside the application or process under test.
   */
  public static ViewAction pressBackUnconditionally() {
    return actionWithAssertions(new PressBackAction(false));
  }

  /** Returns an action that presses the hardware menu key. */
  public static ViewAction pressMenuKey() {
    return pressKey(KeyEvent.KEYCODE_MENU);
  }

  /**
   * Returns an action that presses the key specified by the keyCode (eg. Keyevent.KEYCODE_BACK).
   */
  public static ViewAction pressKey(int keyCode) {
    return actionWithAssertions(
        new KeyEventAction(new EspressoKey.Builder().withKeyCode(keyCode).build()));
  }

  /** Returns an action that presses the specified key with the specified modifiers. */
  public static ViewAction pressKey(EspressoKey key) {
    return actionWithAssertions(new KeyEventAction(key));
  }

  /**
   * Returns an action that double clicks the view.<br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction doubleClick() {
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.DOUBLE,
            GeneralLocation.CENTER,
            Press.FINGER,
            InputDevice.SOURCE_UNKNOWN,
            MotionEvent.BUTTON_PRIMARY));
  }

  /**
   * Returns an action that long clicks the view.<br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *       <ul>
   */
  public static ViewAction longClick() {
    return actionWithAssertions(
        new GeneralClickAction(
            Tap.LONG,
            GeneralLocation.CENTER,
            Press.FINGER,
            InputDevice.SOURCE_UNKNOWN,
            MotionEvent.BUTTON_PRIMARY));
  }

  /**
   * Returns an action that scrolls to the view.<br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be a descendant of ScrollView
   *   <li>must have visibility set to View.VISIBLE
   *       <ul>
   */
  public static ViewAction scrollTo() {
    return actionWithAssertions(new ScrollToAction());
  }

  /**
   * Returns an action that types the provided string into the view. Appending a \n to the end of
   * the string translates to a ENTER key event. Note: this method does not change cursor position
   * in the focused view - text is inserted at the location where the cursor is currently pointed.
   * <br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *   <li>must support input methods
   *   <li>must be already focused
   *       <ul>
   */
  public static ViewAction typeTextIntoFocusedView(String stringToBeTyped) {
    return actionWithAssertions(new TypeTextAction(stringToBeTyped, false /* tapToFocus */));
  }

  /**
   * Returns an action that selects the view (by clicking on it) and types the provided string into
   * the view. Appending a \n to the end of the string translates to a ENTER key event. Note: this
   * method performs a tap on the view before typing to force the view into focus, if the view
   * already contains text this tap may place the cursor at an arbitrary position within the text.
   * <br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *   <li>must support input methods
   *       <ul>
   */
  public static ViewAction typeText(String stringToBeTyped) {
    return actionWithAssertions(new TypeTextAction(stringToBeTyped));
  }

  /**
   * Returns an action that updates the text attribute of a view. <br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *   <li>must be assignable from EditText
   *       <ul>
   */
  public static ViewAction replaceText(@Nonnull String stringToBeSet) {
    return actionWithAssertions(new ReplaceTextAction(stringToBeSet));
  }

  /**
   * Same as {@code openLinkWithText(Matcher<String> linkTextMatcher)}, but uses {@code
   * is(linkText)} as the linkTextMatcher.
   */
  public static ViewAction openLinkWithText(String linkText) {
    return openLinkWithText(is(linkText));
  }

  /**
   * Same as {@code openLink(Matcher<String> linkTextMatcher, Matcher<Uri> uriMatcher)}, but uses
   * {@code any(Uri.class)} as the uriMatcher.
   */
  public static ViewAction openLinkWithText(Matcher<String> linkTextMatcher) {
    return openLink(linkTextMatcher, any(Uri.class));
  }

  /**
   * Same as {@code openLinkWithUri(Matcher<Uri> uriMatcher)}, but uses {@code is(uri)} as the
   * uriMatcher.
   */
  public static ViewAction openLinkWithUri(String uri) {
    return openLinkWithUri(is(Uri.parse(uri)));
  }

  /**
   * Same as {@code openLink(Matcher<String> linkTextMatcher, Matcher<Uri> uriMatcher)}, but uses
   * {@code any(String.class)} as the linkTextMatcher.
   */
  public static ViewAction openLinkWithUri(Matcher<Uri> uriMatcher) {
    return openLink(any(String.class), uriMatcher);
  }

  /**
   * Returns an action that opens a link matching the given link text and uri matchers. The action
   * is performed by invoking the link's onClick method (as opposed to actually issuing a click on
   * the screen). <br>
   * <br>
   * View preconditions:
   *
   * <ul>
   *   <li>must be displayed on screen
   *   <li>must be assignable from TextView
   *   <li>must have links
   *       <ul>
   */
  public static ViewAction openLink(Matcher<String> linkTextMatcher, Matcher<Uri> uriMatcher) {
    checkNotNull(linkTextMatcher);
    checkNotNull(uriMatcher);
    return actionWithAssertions(new OpenLinkAction(linkTextMatcher, uriMatcher));
  }

  /**
   * Returns an action that performs given {@code ViewAction} on the view until view matches the
   * desired {@code Matcher<View>}. It will repeat the given action until view matches the desired
   * {@code Matcher<View>} or PerformException will be thrown if given number of unsuccessful
   * attempts are made.
   *
   * @param action action to be performed repeatedly
   * @param desiredStateMatcher action is performed repeatedly until view matches this view matcher
   * @param maxAttempts max number of times for which this action to be performed if view doesn't
   *     match the given view matcher
   */
  public static ViewAction repeatedlyUntil(
      final ViewAction action, final Matcher<View> desiredStateMatcher, final int maxAttempts) {
    checkNotNull(action);
    checkNotNull(desiredStateMatcher);
    return actionWithAssertions(
        new RepeatActionUntilViewState(action, desiredStateMatcher, maxAttempts));
  }
}
