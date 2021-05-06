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

package androidx.test.espresso.base;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.view.View;
import androidx.test.espresso.FailureHandler;
import androidx.test.espresso.internal.inject.TargetContext;
import androidx.test.internal.platform.io.PlatformTestStorage;
import androidx.test.internal.platform.util.TestOutputEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.hamcrest.Matcher;

/**
 * Espresso's default {@link FailureHandler}. If this does not fit your needs, feel free to provide
 * your own implementation via Espresso.setFailureHandler(FailureHandler).
 */
public final class DefaultFailureHandler implements FailureHandler {

  private static final AtomicInteger failureCount = new AtomicInteger(0);
  private final List<FailureHandler> handlers = new ArrayList<>();

  @Inject
  public DefaultFailureHandler(@TargetContext Context appContext, PlatformTestStorage testStorage) {
    // Adds a chain of exception handlers.
    // Order matters and a matching failure handler in the chain will throw after the exception is
    // handled. Always adds the handler of the child class ahead of its superclasses to make sure
    // the exception is handled its corresponding handler.
    //
    // The hierarchy of the exception types handled is:
    //
    // NoMatchingViewException -->
    // PerformException ---------> EspressoException --> Throwable
    // AssertionError --------------------------------->
    handlers.add(new NoMatchingViewExceptionHandler(testStorage, failureCount));
    handlers.add(new PerformExceptionHandler(checkNotNull(appContext)));
    handlers.add(new AssertionErrorHandler());
    handlers.add(new EspressoExceptionHandler());
    handlers.add(new ThrowableHandler());
  }

  @Override
  public void handle(Throwable error, Matcher<View> viewMatcher) {
    int count = failureCount.incrementAndGet();
    TestOutputEmitter.takeScreenshot("view-op-error-" + count + ".png");
    TestOutputEmitter.captureWindowHierarchy("explore-window-hierarchy-" + count + ".xml");

    // Iterates through the list of handlers to handle the exception, but at most one handler will
    // update the exception and throw at the end of the handling.
    for (FailureHandler handler : handlers) {
      handler.handle(error, viewMatcher);
    }
  }
}
