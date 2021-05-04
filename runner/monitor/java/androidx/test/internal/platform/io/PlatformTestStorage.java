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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;

/**
 * Represents the test storage APIs used in the AndroidX Test library.
 *
 * <p>This is an internal API. Any AndroidX Test code that needs to handle I/O should try to rely on
 * this interface to avoid possible friction when running on different testing environments, e.g.
 * real devices/emulators/Robolectric/etc.
 */
public interface PlatformTestStorage {
  /**
   * Returns an {@code InputStream} to the test input file represented by the given file path.
   *
   * @param pathname the file path to read from.
   * @throws IOException when I/O error occurred.
   */
  InputStream openInputFile(String pathname) throws IOException;

  /**
   * Returns an {@code OutputStream} to the test output file represented by the given file path.
   *
   * @param pathname the file path to write to.
   * @throws IOException when I/O error occurred.
   */
  OutputStream openOutputFile(String pathname) throws IOException;

  /**
   * Returns an InputStream to an internal file used by the testing infrastructure.
   *
   * @param pathname path to the internal file. Should not be null. This is a relative path to where
   *     the storage service stores the internal files. For example, if the storage service stores
   *     the input files under "/sdcard/internal_only", with a pathname "/path/to/my_input.txt", the
   *     file will end up at "/sdcard/internal_only/path/to/my_input.txt" on device.
   * @return an InputStream to the given test file.
   */
  InputStream openInternalInputStream(String pathname) throws IOException;

  /**
   * Returns an OutputStream to an internal file used by the testing infrastructure.
   *
   * @param pathname path to the internal file. Should not be null. This is a relative path to where
   *     the storage service stores the output files. For example, if the storage service stores the
   *     output files under "/sdcard/internal_only", with a pathname "/path/to/my_output.txt", the
   *     file will end up at "/sdcard/internal_only/path/to/my_output.txt" on device.
   * @return an OutputStream to the given output file.
   */
  OutputStream openInternalOutputStream(String pathname) throws IOException;

  /**
   * Adds the given properties.
   *
   * <p>Adding a property with the same name would append new values and overwrite the old values if
   * keys already exist.
   *
   * @return true if the given properties are successfully added, otherwise false.
   */
  boolean addOutputProperties(Map<String, Serializable> properties);

  /**
   * Adds a test artifact file with the given content. The default charset is used in encoding the
   * content.
   *
   * @param pathname the file path to write to.
   * @param content the content of the file.
   * @return true if the given content was successfully written to the specified file, otherwise
   *     false.
   */
  boolean addOutputFile(String pathname, String content);
}
