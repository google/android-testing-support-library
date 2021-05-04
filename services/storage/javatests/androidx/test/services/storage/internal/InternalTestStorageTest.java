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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.services.storage.TestStorage;
import com.google.common.io.CharStreams;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test cases for {@link InternalTestStorage}. */
@RunWith(JUnit4.class)
public class InternalTestStorageTest {
  private static final String OUTPUT_PATH = "parent_dir/output_file";
  private InternalTestStorage internalTestStorage;
  private TestStorage testStorageDelegate;

  @Before
  public void setUp() throws FileNotFoundException {
    testStorageDelegate = new TestStorage();
    internalTestStorage = new InternalTestStorage(testStorageDelegate);
  }

  @Test
  public void readAndWriteInternalFile() throws Exception {
    OutputStream rawStream = internalTestStorage.openInternalOutputStream(OUTPUT_PATH);
    Writer writer = new BufferedWriter(new OutputStreamWriter(rawStream));
    try {
      writer.write("Four score and 7 years ago\n");
      writer.write("Our forefathers executed some tests.");
    } finally {
      writer.close();
    }

    // Checks the content is correctly written.
    try (InputStream in = internalTestStorage.openInternalInputStream(OUTPUT_PATH)) {
      String content = CharStreams.toString(new InputStreamReader(in));
      assertThat(content)
          .isEqualTo("Four score and 7 years ago\nOur forefathers executed some tests.");
    }
  }

  @Test
  public void addOutputFile() throws IOException {
    boolean fileCreated =
        internalTestStorage.addOutputFile("path/to/output_file", "espresso_test_data");
    assertThat(fileCreated).isTrue();
  }

  @Test
  public void addOutputProperties() {
    Map<String, Serializable> properties = new HashMap<>();
    properties.put("key1", "value1");
    properties.put("key2", "value2");

    boolean propertiesAdded = internalTestStorage.addOutputProperties(properties);
    assertThat(propertiesAdded).isTrue();
  }
}
