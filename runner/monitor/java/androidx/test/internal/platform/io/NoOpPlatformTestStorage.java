/*
 * Copyright (C) 2021 The Android Open Source Project
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
package androidx.test.internal.platform.io;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;

/** A test storage that does nothing. All the I/O operations in this class are ignored. */
public final class NoOpPlatformTestStorage implements PlatformTestStorage {

  @Override
  public InputStream openInputFile(String pathname) {
    return new NullInputStream();
  }

  @Override
  public OutputStream openOutputFile(String pathname) {
    return new NullOutputStream();
  }

  @Override
  public InputStream openInternalInputStream(String pathname) {
    return new NullInputStream();
  }

  @Override
  public OutputStream openInternalOutputStream(String pathname) {
    return new NullOutputStream();
  }

  @Override
  public boolean addOutputFile(String pathname, String data) {
    return false;
  }

  @Override
  public boolean addOutputProperties(Map<String, Serializable> properties) {
    return false;
  }

  static class NullInputStream extends InputStream {
    @Override
    public int read() {
      return 0;
    }
  }

  static class NullOutputStream extends OutputStream {
    @Override
    public void write(int b) {}
  }
}
