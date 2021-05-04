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
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import androidx.test.internal.platform.io.PlatformTestStorage;
import androidx.test.services.storage.TestStorage;
import androidx.test.services.storage.TestStorageException;
import androidx.test.services.storage.file.HostedFile;
import androidx.test.services.storage.file.HostedFile.FileHost;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;
import javax.annotation.Nonnull;

/** Test storage APIs meant to be used internally in androidx.test. */
public final class InternalTestStorage implements PlatformTestStorage {
  private static final String TAG = InternalTestStorage.class.getSimpleName();

  private final ContentResolver contentResolver;
  private final TestStorage testStorage;

  /**
   * Default constructor.
   *
   * <p>This class is supposed to be used in the Instrumentation process, e.g. in an Android
   * Instrumentation test. Thus by default, we use the content resolver of the app under test as the
   * one to resolve a URI in this storage service.
   */
  public InternalTestStorage() {
    this(new TestStorage());
  }

  @VisibleForTesting
  InternalTestStorage(TestStorage testStorage) {
    contentResolver = getInstrumentation().getTargetContext().getContentResolver();
    this.testStorage = testStorage;
  }

  @Override
  public InputStream openInputFile(String pathname) throws IOException {
    return testStorage.openInputFile(pathname);
  }

  @Override
  public OutputStream openOutputFile(String pathname) throws IOException {
    return testStorage.openOutputFile(pathname);
  }

  @Override
  public boolean addOutputFile(String pathname, String data) {
    try (OutputStream out = testStorage.openOutputFile(pathname)) {
      out.write(data.getBytes());
      return true;
    } catch (IOException e) {
      Log.d(
          TAG,
          "Error occurred during adding the output file "
              + pathname
              + "! This could happen when the test storage service is not available. Ignore.");
    }
    return false;
  }

  @Override
  public boolean addOutputProperties(Map<String, Serializable> properties) {
    try {
      testStorage.addOutputProperties(properties);
      return true;
    } catch (TestStorageException e) {
      Log.d(
          TAG,
          "Exception occurred during dumping test output properties! "
              + "This could happen when the test storage service is not available, Ignore.",
          e);
      return false;
    }
  }

  @Override
  public InputStream openInternalInputStream(@Nonnull String pathname)
      throws FileNotFoundException {
    checkNotNull(pathname);
    Uri outputUri = HostedFile.buildUri(FileHost.INTERNAL_USE_ONLY, pathname);
    return TestStorageUtil.getInputStream(outputUri, contentResolver);
  }

  @Override
  public OutputStream openInternalOutputStream(@Nonnull String pathname)
      throws FileNotFoundException {
    checkNotNull(pathname);
    Uri outputUri = HostedFile.buildUri(FileHost.INTERNAL_USE_ONLY, pathname);
    return TestStorageUtil.getOutputStream(outputUri, contentResolver);
  }
}
