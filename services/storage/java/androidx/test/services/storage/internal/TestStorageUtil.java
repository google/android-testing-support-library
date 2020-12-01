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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import androidx.test.services.storage.TestStorageException;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/** Helper methods for the test storage implementations. */
public final class TestStorageUtil {
  /**
   * Gets the input stream for a given Uri.
   *
   * @param uri The Uri for which the InputStream is required.
   */
  public static InputStream getInputStream(Uri uri, ContentResolver contentResolver)
      throws FileNotFoundException {
    checkNotNull(uri);

    ContentProviderClient providerClient = null;
    try {
      providerClient = makeContentProviderClient(contentResolver, uri);
      // Assignment to a variable is required. Do not inline.
      ParcelFileDescriptor pfd = providerClient.openFile(uri, "r");
      // Buffered to improve performance.
      return new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pfd));
    } catch (RemoteException re) {
      throw new TestStorageException("Unable to access content provider: " + uri, re);
    } finally {
      if (providerClient != null) {
        // Uses #release() to be compatible with API < 24.
        providerClient.release();
      }
    }
  }

  /**
   * Gets the output stream for a given Uri.
   *
   * <p>The returned OutputStream is essentially a {@link java.io.FileOutputStream} which likely
   * should be buffered to avoid {@code UnbufferedIoViolation} when running under strict mode.
   *
   * @param uri The Uri for which the OutputStream is required.
   */
  public static OutputStream getOutputStream(Uri uri, ContentResolver contentResolver)
      throws FileNotFoundException {
    checkNotNull(uri);

    ContentProviderClient providerClient = null;
    try {
      providerClient = makeContentProviderClient(contentResolver, uri);
      return new ParcelFileDescriptor.AutoCloseOutputStream(providerClient.openFile(uri, "w"));
    } catch (RemoteException re) {
      throw new TestStorageException("Unable to access content provider: " + uri, re);
    } finally {
      if (providerClient != null) {
        // Uses #release() to be compatible with API < 24.
        providerClient.release();
      }
    }
  }

  private static ContentProviderClient makeContentProviderClient(
      ContentResolver resolver, Uri uri) {
    checkNotNull(resolver);

    ContentProviderClient providerClient = resolver.acquireContentProviderClient(uri);
    if (null == providerClient) {
      throw new TestStorageException(
          String.format(
              "No content provider registered for: %s. Are all test services apks installed?",
              uri));
    }
    return providerClient;
  }

  private TestStorageUtil() {}
}
