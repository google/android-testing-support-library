/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An exposed registry instance that holds a reference to the instrumentation running in the process
 * and its arguments.
 *
 * <p>Instrumentation and InstrumentationRegistry are low level APIs, typically used by higher level
 * test frameworks. It is generally not recommended for direct use by most tests.
 */
public final class InstrumentationRegistry {

  private static final AtomicReference<Instrumentation> instrumentationRef =
      new AtomicReference<>(null);
  private static final AtomicReference<Bundle> arguments = new AtomicReference<>(null);
  private static final AtomicReference<InstrumentationProvider> provider =
      new AtomicReference<>(null);

  /**
   * Returns the instrumentation currently running. Use this to get an {@link Instrumentation} into
   * your test.
   *
   * @throws IllegalStateException if instrumentation hasn't been registered
   */
  public static Instrumentation getInstrumentation() {
    synchronized (instrumentationRef) {
      Instrumentation instance = instrumentationRef.get();
      if (instance == null && provider.get() != null) {
        instance = provider.get().provide();
        instrumentationRef.set(instance);
      }
      if (instance == null) {
        throw new IllegalStateException(
            "No instrumentation registered! " + "Must run under a registering instrumentation.");
      }
      return instance;
    }
  }

  /**
   * Returns a copy of instrumentation arguments Bundle. Use this to get a {@link Bundle} containing
   * the command line arguments passed to {@link Instrumentation} into your test.
   *
   * <p>This Bundle is not guaranteed to be present under all instrumentations.
   *
   * @return Bundle the arguments for this instrumentation.
   * @throws IllegalStateException if no argument Bundle has been registered.
   */
  public static Bundle getArguments() {
    Bundle instance = arguments.get();
    if (instance == null) {
      throw new IllegalStateException(
          "No instrumentation arguments registered! "
              + "Are you running under an Instrumentation which registers arguments?");
    }
    return new Bundle(instance);
  }

  /**
   * Records/exposes the instrumentation currently running and stores a copy of the instrumentation
   * arguments Bundle in the registry.
   *
   * <p>This is a global registry - so be aware of the impact of calling this method!
   *
   * @param instrumentation the instrumentation currently running.
   * @param arguments the arguments for this application. Null deregisters any existing arguments.
   */
  public static void registerInstance(Instrumentation instrumentation, Bundle arguments) {
    instrumentationRef.set(instrumentation);
    InstrumentationRegistry.arguments.set(new Bundle(arguments));
  }

  /**
   * Registers an Instrumentation Provider to support lazy loading of Instrumentation
   *
   * <p>This is a global registry - so be aware of the impact of calling this method!
   *
   * <p>Note that this and {@link #registerInstance(Instrumentation, Bundle)}} override each other
   * with respect to which Instrumentation is saved.
   *
   * @deprecated do not use, will be removed shortly
   * @hide
   */
  @Deprecated
  public static void registerInstrumentationProvider(
      InstrumentationProvider instrumentationProvider, Bundle arguments) {
    provider.set(instrumentationProvider);
    InstrumentationRegistry.arguments.set(new Bundle(arguments));
    instrumentationRef.set(null);
  }

  private InstrumentationRegistry() {}
}
