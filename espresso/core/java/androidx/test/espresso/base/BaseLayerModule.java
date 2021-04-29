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

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.test.espresso.FailureHandler;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.base.IdlingResourceRegistry.IdleNotificationCallback;
import androidx.test.espresso.internal.inject.TargetContext;
import androidx.test.internal.platform.ServiceLoaderWrapper;
import androidx.test.internal.platform.os.ControlledLooper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorage;
import androidx.test.platform.io.PlatformTestStorageRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dagger.Module;
import dagger.Provides;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Dagger module for creating the implementation classes within the base package.
 *
 * @hide
 */
@Module
public class BaseLayerModule {

  private static final String TAG = BaseLayerModule.class.getSimpleName();

  @Provides
  public ActivityLifecycleMonitor provideLifecycleMonitor() {
    return ActivityLifecycleMonitorRegistry.getInstance();
  }

  @Provides
  @TargetContext
  public Context provideTargetContext() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Provides
  @Singleton
  public Looper provideMainLooper() {
    return Looper.getMainLooper();
  }

  @Provides
  @Singleton
  @CompatAsyncTask
  public IdleNotifier<Runnable> provideCompatAsyncTaskMonitor(
      ThreadPoolExecutorExtractor extractor) {
    Optional<ThreadPoolExecutor> compatThreadPool = extractor.getCompatAsyncTaskThreadPool();
    if (compatThreadPool.isPresent()) {
      return new AsyncTaskPoolMonitor(compatThreadPool.get()).asIdleNotifier();
    } else {
      return new NoopRunnableIdleNotifier();
    }
  }

  @Provides
  @Singleton
  @MainThread
  public Executor provideMainThreadExecutor(Looper mainLooper) {
    final Handler handler = new Handler(mainLooper);
    return new Executor() {
      @Override
      public void execute(Runnable runnable) {
        handler.post(runnable);
      }
    };
  }

  @Provides
  public IdleNotifier<IdleNotificationCallback> provideDynamicNotifer(
      IdlingResourceRegistry dynamicRegistry) {
    // Since a dynamic notifier will be created for each Espresso interaction this is a good time
    // to sync the IdlingRegistry with IdlingResourceRegistry.
    dynamicRegistry.sync(
        IdlingRegistry.getInstance().getResources(), IdlingRegistry.getInstance().getLoopers());
    return dynamicRegistry.asIdleNotifier();
  }

  @Provides
  @Singleton
  @SdkAsyncTask
  public IdleNotifier<Runnable> provideSdkAsyncTaskMonitor(ThreadPoolExecutorExtractor extractor) {
    return new AsyncTaskPoolMonitor(extractor.getAsyncTaskThreadPool()).asIdleNotifier();
  }

  @Provides
  public ActiveRootLister provideActiveRootLister(RootsOracle rootsOracle) {
    return rootsOracle;
  }

  @Provides
  @Singleton
  public EventInjector provideEventInjector() {
    // On API 16 and above, android uses input manager to inject events. On API < 16,
    // they use Window Manager. So we need to create our InjectionStrategy depending on the api
    // level. Instrumentation does not check if the event presses went through by checking the
    // boolean return value of injectInputEvent, which is why we created this class to better
    // handle lost/dropped press events. Instrumentation cannot be used as a fallback strategy,
    // since this will be executed on the main thread.
    int sdkVersion = Build.VERSION.SDK_INT;
    EventInjectionStrategy injectionStrategy = null;
    if (sdkVersion >= 16) { // Use InputManager for API level 16 and up.
      InputManagerEventInjectionStrategy strategy = new InputManagerEventInjectionStrategy();
      strategy.initialize();
      injectionStrategy = strategy;
    } else if (sdkVersion >= 7) {
      // else Use WindowManager for API level 15 through 7.
      WindowManagerEventInjectionStrategy strategy = new WindowManagerEventInjectionStrategy();
      strategy.initialize();
      injectionStrategy = strategy;
    } else {
      throw new RuntimeException(
          "API Level 6 and below is not supported. You are running: " + sdkVersion);
    }
    return new EventInjector(injectionStrategy);
  }

  /** Holder for AtomicReference<FailureHandler> which allows updating it at runtime. */
  @Singleton
  public static class FailureHandlerHolder {
    private final AtomicReference<FailureHandler> holder;

    @Inject
    public FailureHandlerHolder(@Default FailureHandler defaultHandler) {
      holder = new AtomicReference<FailureHandler>(defaultHandler);
    }

    public void update(FailureHandler handler) {
      holder.set(handler);
    }

    public FailureHandler get() {
      return holder.get();
    }
  }

  @Provides
  FailureHandler provideFailureHandler(FailureHandlerHolder holder) {
    return holder.get();
  }

  @Provides
  @Singleton
  public ListeningExecutorService provideRemoteExecutor() {
    return MoreExecutors.listeningDecorator(
        new ThreadPoolExecutor(
            0 /*corePoolSize*/,
            5 /*maximumPoolSize*/,
            10,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadFactoryBuilder().setNameFormat("Espresso Remote #%d").build()));
  }

  @Provides
  @Default
  FailureHandler provideFailureHander(DefaultFailureHandler impl) {
    return impl;
  }

  @Provides
  DefaultFailureHandler provideDefaultFailureHander(@TargetContext Context context) {
    return new DefaultFailureHandler(context);
  }

  @Provides
  @Singleton
  public ControlledLooper provideControlledLooper() {
    // load a service loaded provided ControlledLooper if available, otherwise return a no-op
    return ServiceLoaderWrapper.loadSingleService(
        ControlledLooper.class, () -> ControlledLooper.NO_OP_CONTROLLED_LOOPER);
  }

  @Provides
  @Singleton
  PlatformTestStorage provideTestStorage() {
    PlatformTestStorage testStorage =
        ServiceLoaderWrapper.loadSingleServiceOrNull(PlatformTestStorage.class);
    if (testStorage != null) {
      Log.d(TAG, "The platform test storage instance is loaded by the service loader.");
      // Uses the instance loaded by the service loader if available.
      return testStorage;
    } else {
      return PlatformTestStorageRegistry.getInstance();
    }
  }
}
