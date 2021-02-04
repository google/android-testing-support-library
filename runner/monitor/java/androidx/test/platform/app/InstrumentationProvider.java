package androidx.test.platform.app;

import android.app.Instrumentation;

/**
 * Interface to allow runner implementations to provide an <code>Instrumentation</code> as needed,
 * rather than on every test start.
 */
public interface InstrumentationProvider {

  /**
   * Returns the <code>Instrumentation</code> backed by this <code>InstrumentationProvider</code>>.
   */
  Instrumentation provide();
}
