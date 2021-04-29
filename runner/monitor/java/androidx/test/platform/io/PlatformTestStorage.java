/*
 * Copyright (C) 2021 The Android Open Source Project
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
package androidx.test.platform.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;

/**
 * Provides convenient I/O operations for reading/writing testing relevant files, properties in a
 * test.
 */
public interface PlatformTestStorage {
  /**
   * Provides an InputStream to a test file dependency.
   *
   * @param pathname path to the test file dependency. Should not be null. This is a relative path
   *     to where the storage service stores the input files. For example, if the storage service
   *     stores the input files under "/sdcard/test_input_files", with a pathname
   *     "/path/to/my_input.txt", the file will end up at
   *     "/sdcard/test_input_files/path/to/my_input.txt" on device.
   * @return an InputStream to the given test file.
   */
  InputStream openInputFile(String pathname) throws IOException;

  /**
   * Returns the value of a given argument name.
   *
   * <p>There should be one and only one argument defined with the given argument name. Otherwise,
   * it will throw a TestStorageException if zero or more than one arguments are found.
   *
   * <p>We suggest using some naming convention when defining the argument name to avoid possible
   * conflict, e.g. defining "namespaces" for your arguments which helps clarify how the argument is
   * used and also its scope. For example, for arguments used for authentication purposes, you could
   * name the account email argument as something like "google_account.email" and its password as
   * "google_account.password".
   *
   * @param argName the argument name. Should not be null.
   */
   String getInputArg(String argName);

  /**
   * Returns the name/value map of all test arguments or an empty map if no arguments are defined.
   */
  Map<String, String> getInputArgs();

  /**
   * Provides an OutputStream to a test output file.
   *
   * @param pathname path to the test output file. Should not be null. This is a relative path to
   *     where the storage service stores the output files. For example, if the storage service
   *     stores the output files under "/sdcard/test_output_files", with a pathname
   *     "/path/to/my_output.txt", the file will end up at
   *     "/sdcard/test_output_files/path/to/my_output.txt" on device.
   * @return an OutputStream to the given output file.
   */
  OutputStream openOutputFile(String pathname) throws IOException;

  /**
   * Adds the given properties.
   *
   * <p>Adding a property with the same name would append new values and overwrite the old values if
   * keys already exist.
   */
  void addOutputProperties(Map<String, Serializable> properties);

  /**
   * Returns a map of all the output test properties. If no properties exist, an empty map will be
   * returned.
   */
  Map<String, Serializable> getOutputProperties();
}
