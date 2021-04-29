/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.espresso;

import androidx.test.espresso.base.ActiveRootLister;
import androidx.test.espresso.base.BaseLayerModule;
import androidx.test.espresso.base.IdlingResourceRegistry;
import androidx.test.espresso.base.MainThread;
import androidx.test.espresso.base.UiControllerModule;
import androidx.test.internal.platform.os.ControlledLooper;
import androidx.test.platform.io.PlatformTestStorage;
import dagger.Component;
import java.util.concurrent.Executor;
import javax.inject.Singleton;

/** Dagger component for base classes. */
@Component(modules = {BaseLayerModule.class, UiControllerModule.class})
@Singleton
public interface BaseLayerComponent {
  BaseLayerModule.FailureHandlerHolder failureHolder();

  FailureHandler failureHandler();

  ActiveRootLister activeRootLister();

  IdlingResourceRegistry idlingResourceRegistry();

  ViewInteractionComponent plus(ViewInteractionModule module);

  UiController uiController();

  @MainThread
  Executor mainThreadExecutor();

  ControlledLooper controlledLooper();

  PlatformTestStorage testStorage();
}
