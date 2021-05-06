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

import androidx.test.espresso.base.ActiveRootLister;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry;
import androidx.test.internal.platform.tracker.UsageTrackerRegistry.AxtVersions;
import androidx.test.internal.platform.util.TestOutputEmitter;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Holds Espresso's object graph. */
public final class GraphHolder {

  private static final AtomicReference<GraphHolder> instance =
      new AtomicReference<GraphHolder>(null);

  private final BaseLayerComponent component;

  private GraphHolder(BaseLayerComponent component) {
    this.component = checkNotNull(component);
  }

  static BaseLayerComponent baseLayer() {
    GraphHolder instanceRef = instance.get();
    if (null == instanceRef) {
      instanceRef = new GraphHolder(DaggerBaseLayerComponent.create());
      if (instance.compareAndSet(null, instanceRef)) {
        UsageTrackerRegistry.getInstance().trackUsage("Espresso", AxtVersions.ESPRESSO_VERSION);
        // Also adds the usage data as test output properties. By default it's no-op.
        Map<String, Serializable> usageProperties = new HashMap<>();
        usageProperties.put("Espresso", AxtVersions.ESPRESSO_VERSION);
        TestOutputEmitter.addOutputProperties(usageProperties);
        return instanceRef.component;
      } else {
        return instance.get().component;
      }
    } else {
      return instanceRef.component;
    }
  }
}
