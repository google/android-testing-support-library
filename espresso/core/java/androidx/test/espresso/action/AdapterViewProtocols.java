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

import static androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static com.google.common.base.Preconditions.checkArgument;

import android.database.Cursor;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.AdapterViewAnimator;
import android.widget.AdapterViewFlipper;
import androidx.test.espresso.util.EspressoOptional;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import java.util.List;

/** Implementations of {@link AdapterViewProtocol} for standard SDK Widgets. */
public final class AdapterViewProtocols {

  /**
   * Consider views which have over this percentage of their area visible to the user to be fully
   * rendered.
   */
  private static final int FULLY_RENDERED_PERCENTAGE_CUTOFF = 90;

  private AdapterViewProtocols() {}

  private static final AdapterViewProtocol STANDARD_PROTOCOL = new StandardAdapterViewProtocol();

  /**
   * Creates an implementation of AdapterViewProtocol that can work with AdapterViews that do not
   * break method contracts on AdapterView.
   */
  public static AdapterViewProtocol standardProtocol() {
    return STANDARD_PROTOCOL;
  }

  private static final class StandardAdapterViewProtocol implements AdapterViewProtocol {

    private static final String TAG = "StdAdapterViewProtocol";

    /** Required for Espresso remote serialization, called reflectively. */
    public StandardAdapterViewProtocol() {}

    private static final class StandardDataFunction implements DataFunction {
      private final Object dataAtPosition;
      private final int position;

      private StandardDataFunction(Object dataAtPosition, int position) {
        checkArgument(position >= 0, "position must be >= 0");
        this.dataAtPosition = dataAtPosition;
        this.position = position;
      }

      @Override
      public Object getData() {
        if (dataAtPosition instanceof Cursor) {
          if (!((Cursor) dataAtPosition).moveToPosition(position)) {
            Log.e(TAG, "Cannot move cursor to position: " + position);
          }
        }
        return dataAtPosition;
      }
    }

    @Override
    public Iterable<AdaptedData> getDataInAdapterView(AdapterView<? extends Adapter> adapterView) {
      List<AdaptedData> datas = Lists.newArrayList();
      for (int i = 0; i < adapterView.getCount(); i++) {
        int position = i;
        Object dataAtPosition = adapterView.getItemAtPosition(position);
        datas.add(
            new AdaptedData.Builder()
                .withDataFunction(new StandardDataFunction(dataAtPosition, position))
                .withOpaqueToken(position)
                .build());
      }
      return datas;
    }

    @Override
    public EspressoOptional<AdaptedData> getDataRenderedByView(
        AdapterView<? extends Adapter> adapterView, View descendantView) {
      if (adapterView == descendantView.getParent()) {
        int position = adapterView.getPositionForView(descendantView);
        if (position != AdapterView.INVALID_POSITION) {
          return EspressoOptional.of(
              new AdaptedData.Builder()
                  .withDataFunction(
                      new StandardDataFunction(adapterView.getItemAtPosition(position), position))
                  .withOpaqueToken(Integer.valueOf(position))
                  .build());
        }
      }
      return EspressoOptional.absent();
    }

    @Override
    public void makeDataRenderedWithinAdapterView(
        AdapterView<? extends Adapter> adapterView, AdaptedData data) {
      checkArgument(data.opaqueToken instanceof Integer, "Not my data: %s", data);
      int position = ((Integer) data.opaqueToken).intValue();

      boolean moved = false;
      // set selection should always work, we can give a little better experience if per subtype
      // though.
      if (Build.VERSION.SDK_INT > 7) {
        if (adapterView instanceof AbsListView) {
          if (Build.VERSION.SDK_INT > 10) {
            ((AbsListView) adapterView)
                .smoothScrollToPositionFromTop(position, adapterView.getPaddingTop(), 0);
          } else {
            ((AbsListView) adapterView).smoothScrollToPosition(position);
          }
          moved = true;
        }
        if (Build.VERSION.SDK_INT > 10) {
          if (adapterView instanceof AdapterViewAnimator) {
            if (adapterView instanceof AdapterViewFlipper) {
              ((AdapterViewFlipper) adapterView).stopFlipping();
            }
            ((AdapterViewAnimator) adapterView).setDisplayedChild(position);
            moved = true;
          }
        }
      }
      if (!moved) {
        adapterView.setSelection(position);
      }
    }

    @Override
    public boolean isDataRenderedWithinAdapterView(
        AdapterView<? extends Adapter> adapterView, AdaptedData adaptedData) {
      checkArgument(adaptedData.opaqueToken instanceof Integer, "Not my data: %s", adaptedData);
      int dataPosition = ((Integer) adaptedData.opaqueToken).intValue();
      boolean inView = false;

      if (Range.closed(adapterView.getFirstVisiblePosition(), adapterView.getLastVisiblePosition())
          .contains(dataPosition)) {
        if (adapterView.getFirstVisiblePosition() == adapterView.getLastVisiblePosition()) {
          // thats a huge element.
          inView = true;
        } else {
          inView =
              isElementFullyRendered(
                  adapterView, dataPosition - adapterView.getFirstVisiblePosition());
        }
      }
      if (inView) {
        // stops animations - locks in our x/y location.
        adapterView.setSelection(dataPosition);
      }

      return inView;
    }

    private boolean isElementFullyRendered(
        AdapterView<? extends Adapter> adapterView, int childAt) {
      View element = adapterView.getChildAt(childAt);
      // Occasionally we'll have to fight with smooth scrolling logic on our definition of when
      // there is extra scrolling to be done. In particular if the element is the first or last
      // element of the list, the smooth scroller may decide that no work needs to be done to scroll
      // to the element if a certain percentage of it is on screen. Ugh. Sigh. Yuck.

      return isDisplayingAtLeast(FULLY_RENDERED_PERCENTAGE_CUTOFF).matches(element);
    }
  }
}
