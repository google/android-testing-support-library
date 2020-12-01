/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.test.services.storage.internal;

import static androidx.test.internal.util.Checks.checkNotNull;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.ContentResolver;
import android.net.Uri;
import androidx.test.services.storage.file.HostedFile;
import androidx.test.services.storage.file.HostedFile.FileHost;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nonnull;

/** Test storage APIs meant to be used internally in androidx.test. */
public final class InternalTestStorage {
  private final ContentResolver contentResolver;

  /**
   * Default constructor.
   *
   * <p>This class is supposed to be used in the Instrumentation process, e.g. in an Android
   * Instrumentation test. Thus by default, we use the content resolver of the app under test as the
   * one to resolve a URI in this storage service.
   */
  public InternalTestStorage() {
    contentResolver = getInstrumentation().getTargetContext().getContentResolver();
  }

  /**
   * Provides an InputStream to an internal file used by the testing infrastructure.
   *
   * @param pathname path to the internal file. Should not be null. This is a relative path to where
   *     the storage service stores the internal files. For example, if the storage service stores
   *     the input files under "/sdcard/internal_only", with a pathname "/path/to/my_input.txt", the
   *     file will end up at "/sdcard/internal_only/path/to/my_input.txt" on device.
   * @return an InputStream to the given test file.
   * @hide
   */
  public InputStream openInternalInputStream(@Nonnull String pathname)
      throws FileNotFoundException {
    checkNotNull(pathname);
    Uri outputUri = HostedFile.buildUri(FileHost.INTERNAL_USE_ONLY, pathname);
    return TestStorageUtil.getInputStream(outputUri, contentResolver);
  }

  /**
   * Provides an OutputStream to an internal file used by the testing infrastructure.
   *
   * @param pathname path to the internal file. Should not be null. This is a relative path to where
   *     the storage service stores the output files. For example, if the storage service stores the
   *     output files under "/sdcard/internal_only", with a pathname "/path/to/my_output.txt", the
   *     file will end up at "/sdcard/internal_only/path/to/my_output.txt" on device.
   * @return an OutputStream to the given output file.
   * @hide
   */
  public OutputStream openInternalOutputStream(@Nonnull String pathname)
      throws FileNotFoundException {
    checkNotNull(pathname);
    Uri outputUri = HostedFile.buildUri(FileHost.INTERNAL_USE_ONLY, pathname);
    return TestStorageUtil.getOutputStream(outputUri, contentResolver);
  }
}
