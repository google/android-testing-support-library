/*
 * Copyright (C) 2012 The Android Open Source Project
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
package androidx.test.internal.runner;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.testing.fixtures.AppLifecycleListener;
import androidx.test.testing.fixtures.BrokenCustomTestFilter;
import androidx.test.testing.fixtures.CustomRunnerBuilder;
import androidx.test.testing.fixtures.CustomTestFilter;
import androidx.test.testing.fixtures.CustomTestFilterTakesBundle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.RunnerBuilder;

/** Unit tests for {@link androidx.test.internal.runner.RunnerArgs}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RunnerArgsTest {

  private Instrumentation instrumentation;

  /** Temp file used for testing */
  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder(getApplicationContext().getCacheDir());

  @Before
  public void setUp() {
    instrumentation = getInstrumentation();
  }

  /** Simple test for parsing test class name */
  @Test
  public void testFromBundle_singleClass() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(1, args.tests.size());
    assertEquals("ClassName", args.tests.get(0).testClassName);
    assertNull(args.tests.get(0).methodName);
  }

  /** Test parsing bundle when multiple class names are provided. */
  @Test
  public void testFromBundle_multiClass() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1,ClassName2");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(2, args.tests.size());
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("ClassName2", args.tests.get(1).testClassName);
    assertNull(args.tests.get(0).methodName);
    assertNull(args.tests.get(1).methodName);
  }

  /** Test parsing bundle when class name and method name is provided. */
  @Test
  public void testFromBundle_method() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1#method");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("method", args.tests.get(0).methodName);
  }

  /**
   * Test {@link androidx.test.internal.runner.RunnerArgs.Builder#fromBundle(Bundle)} when class
   * name and method name is provided along with an additional class name.
   */
  @Test
  public void testFromBundle_classAndMethodCombo() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1#method,ClassName2");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(2, args.tests.size());
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("method", args.tests.get(0).methodName);
    assertEquals("ClassName2", args.tests.get(1).testClassName);
    assertNull(args.tests.get(1).methodName);
  }

  /** Simple test for parsing test class name */
  @Test
  public void testFromBundle_notSingleClass() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(1, args.notTests.size());
    assertEquals("ClassName", args.notTests.get(0).testClassName);
    assertNull(args.notTests.get(0).methodName);
  }

  /** Test parsing bundle when multiple class names are provided. */
  @Test
  public void testFromBundle_notMultiClass() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1,ClassName2");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(2, args.notTests.size());
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("ClassName2", args.notTests.get(1).testClassName);
    assertNull(args.notTests.get(0).methodName);
    assertNull(args.notTests.get(1).methodName);
  }

  /** Test parsing bundle when class name and method name is provided. */
  @Test
  public void testFromBundle_notMethod() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1#method");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("method", args.notTests.get(0).methodName);
  }

  /**
   * Test {@link androidx.test.internal.runner.RunnerArgs.Builder#fromBundle(Bundle)} when class
   * name and method name is provided along with an additional class name.
   */
  @Test
  public void testFromBundle_notClassAndMethodCombo_different() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1#method,ClassName2");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(2, args.notTests.size());
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("method", args.notTests.get(0).methodName);
    assertEquals("ClassName2", args.notTests.get(1).testClassName);
    assertNull(args.notTests.get(1).methodName);
  }

  /**
   * Test {@link androidx.test.internal.runner.RunnerArgs.Builder#fromBundle(Bundle)} when class
   * name and method name is provided along with the same class name again.
   */
  @Test
  public void testFromBundle_notClassAndMethodCombo_same() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1#method,ClassName1");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(2, args.notTests.size());
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("method", args.notTests.get(0).methodName);
    assertEquals("ClassName1", args.notTests.get(1).testClassName);
    assertNull(args.notTests.get(1).methodName);
  }

  /** Test parsing bundle when class name and not class name is provided. */
  @Test
  public void testFromBundle_classAndNotClass_different() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1");
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName2#method");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(1, args.tests.size());
    assertEquals(1, args.notTests.size());
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("ClassName2", args.notTests.get(0).testClassName);
    assertEquals("method", args.notTests.get(0).methodName);
  }

  /** Test parsing bundle when class name and not class name is provided. */
  @Test
  public void testFromBundle_classAndNotClass_same() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1");
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1#method");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(1, args.tests.size());
    assertEquals(1, args.notTests.size());
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("method", args.notTests.get(0).methodName);
  }

  /**
   * Test parsing bundle when package names, class names, and method names are provided within a
   * test file.
   */
  @Test
  public void testFromBundle_testFile() throws Exception {
    final File file = tmpFolder.newFile("myTestFile.txt");
    writeFiltersToFile(file, Arrays.asList("ClassName3", "ClassName4#method2", "pkg.number.two"));
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_FILE, file.getPath());
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "ClassName1#method1,ClassName2");
    b.putString(RunnerArgs.ARGUMENT_TEST_PACKAGE, "pkg.number.one");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(4, args.tests.size());
    assertEquals("ClassName1", args.tests.get(0).testClassName);
    assertEquals("method1", args.tests.get(0).methodName);
    assertEquals("ClassName2", args.tests.get(1).testClassName);
    assertNull(args.tests.get(1).methodName);
    assertEquals("ClassName3", args.tests.get(2).testClassName);
    assertNull(args.tests.get(2).methodName);
    assertEquals("ClassName4", args.tests.get(3).testClassName);
    assertEquals("method2", args.tests.get(3).methodName);
    assertEquals(2, args.testPackages.size());
    assertEquals("pkg.number.one", args.testPackages.get(0));
    assertEquals("pkg.number.two", args.testPackages.get(1));
  }

  /** Test failure reading a testfile */
  @Test(expected = IllegalArgumentException.class)
  @SuppressWarnings("TestExceptionChecker")
  public void testFromBundle_testFileFailure() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TEST_FILE, "idontexist");
    new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
  }

  /**
   * Test parsing bundle when package names, class names, and method names are provided within a not
   * test file.
   */
  @Test
  public void testFromBundle_testNotFile() throws Exception {
    final File file = tmpFolder.newFile("myNotTestFile.txt");
    writeFiltersToFile(file, Arrays.asList("ClassName3", "ClassName4#method2", "pkg.number.two"));
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_FILE, file.getPath());
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "ClassName1#method1,ClassName2");
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_PACKAGE, "pkg.number.one");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(4, args.notTests.size());
    assertEquals("ClassName1", args.notTests.get(0).testClassName);
    assertEquals("method1", args.notTests.get(0).methodName);
    assertEquals("ClassName2", args.notTests.get(1).testClassName);
    assertNull(args.notTests.get(1).methodName);
    assertEquals("ClassName3", args.notTests.get(2).testClassName);
    assertNull(args.notTests.get(2).methodName);
    assertEquals("ClassName4", args.notTests.get(3).testClassName);
    assertEquals("method2", args.notTests.get(3).methodName);
    assertEquals(2, args.notTestPackages.size());
    assertEquals("pkg.number.one", args.notTestPackages.get(0));
    assertEquals("pkg.number.two", args.notTestPackages.get(1));
  }

  /** Test classpathToScan arg is an optional argument. */
  @Test
  public void testFromBundle_testEmptyClasspathToScan() throws Exception {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_CLASSPATH_TO_SCAN, "");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(0, args.classpathToScan.size());
  }

  /** Test classpathToScan is an optional argument. */
  @Test
  public void testFromBundle_testNoClasspathToScan() throws Exception {
    Bundle b = new Bundle();
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(0, args.classpathToScan.size());
  }

  /** Test classpathToScan is properly parsed. */
  @Test
  public void testFromBundle_testSinglePathToScan() throws Exception {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_CLASSPATH_TO_SCAN, "/foo/baz.dex");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(1, args.classpathToScan.size());
    assertTrue(args.classpathToScan.contains("/foo/baz.dex"));
  }

  /** Test classpathToScan is an optional argument. */
  @Test
  public void testFromBundle_testMultiplePathsToScan() throws Exception {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_CLASSPATH_TO_SCAN, "/foo/baz.dex:/foo/bar.dex:/baz/foo.dex");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(3, args.classpathToScan.size());
    assertTrue(args.classpathToScan.contains("/foo/baz.dex"));
    assertTrue(args.classpathToScan.contains("/foo/bar.dex"));
    assertTrue(args.classpathToScan.contains("/baz/foo.dex"));
  }

  private static void writeFiltersToFile(File testFile, List<String> filters) throws IOException {
    BufferedWriter out = null;
    try {
      out = new BufferedWriter(new FileWriter(testFile));
      for (int filterIndex = 0; filterIndex < filters.size(); filterIndex++) {
        out.write(filters.get(filterIndex));
        if (filterIndex != filters.size() - 1) {
          out.write("\n"); // separate each filter by a new line
        }
      }
    } finally {
      if (out != null) {
        out.close();
      }
    }
  }

  /** Test failure reading a notTestfile */
  @Test(expected = IllegalArgumentException.class)
  @SuppressWarnings("TestExceptionChecker")
  public void testFromBundle_testNotFileFailure() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_FILE, "idontexist");
    new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
  }

  /** Test static method isClassOrMethod in RunnerArgs.Builder with valid class or method names */
  @Test
  public void isClassOrMethod() throws Exception {
    // test valid class or method names
    assertTrue(RunnerArgs.Builder.isClassOrMethod("pkg.foo.Bar"));
    assertTrue(RunnerArgs.Builder.isClassOrMethod("pkg.foo.Bar#method1"));
    assertTrue(RunnerArgs.Builder.isClassOrMethod("Bar"));
    assertTrue(RunnerArgs.Builder.isClassOrMethod("Bar#method_$1"));
    assertTrue(RunnerArgs.Builder.isClassOrMethod("pkg.foo_1.foo$.Bar2#m"));

    // test parameterized name
    assertTrue(RunnerArgs.Builder.isClassOrMethod("pkg.foo.Bar#method[0]"));
  }

  /** Test static method isClassOrMethod in RunnerArgs.Builder */
  @Test
  public void notClassOrMethod() throws Exception {
    // test valid package names
    assertFalse(RunnerArgs.Builder.isClassOrMethod("pkg.foo.bar"));
    assertFalse(RunnerArgs.Builder.isClassOrMethod("pkg"));
    assertFalse(RunnerArgs.Builder.isClassOrMethod("pkg1.foo_2.bar$3"));

    // invalid names should not register as class or method names
    assertFalse(RunnerArgs.Builder.isClassOrMethod(""));
    assertFalse(RunnerArgs.Builder.isClassOrMethod("$^$%^"));
  }

  /** Test parsing bundle when test timeout is provided */
  @Test
  public void testFromBundle_timeout() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TIMEOUT, "5000"); // 5 seconds
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(5000, args.testTimeout);
  }

  /** Test parsing the boolean debug argument */
  @Test
  public void testFromBundle_debug() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_DEBUG, "true");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertTrue(args.debug);

    b.putString(RunnerArgs.ARGUMENT_DEBUG, "false");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.debug);

    b.putString(RunnerArgs.ARGUMENT_DEBUG, "blargh");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.debug);
  }

  /** Test parsing the boolean logOnly argument */
  @Test
  public void testFromBundle_logOnly() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_LOG_ONLY, "true");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertTrue(args.logOnly);

    b.putString(RunnerArgs.ARGUMENT_LOG_ONLY, "false");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.logOnly);

    b.putString(RunnerArgs.ARGUMENT_LOG_ONLY, "blargh");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.logOnly);
  }

  /** Test that a custom filter with bundle is created */
  @Test
  public void testFromBundle_customFilterWithBundle() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_FILTER, CustomTestFilterTakesBundle.class.getName());
    b.putString(CustomTestFilterTakesBundle.class.getName(), "test");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals("Mismatch in number of filters created", 1, args.filters.size());
    Filter filter = args.filters.get(0);
    assertTrue("Filter not of correct type", filter instanceof CustomTestFilterTakesBundle);
    CustomTestFilterTakesBundle customTestFilterTakesBundle = (CustomTestFilterTakesBundle) filter;
    assertEquals("Filter not of correct type", "test", customTestFilterTakesBundle.getTest());
  }

  /** Test that a custom filter with bundle is created */
  @Test
  public void testFromBundle_brokenCustomFilter() {
    Bundle b = new Bundle();
    String className = BrokenCustomTestFilter.class.getName();
    b.putString(RunnerArgs.ARGUMENT_FILTER, className);
    b.putString(className, "test");
    try {
      new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
      fail("Did not detect invalid filter");
    } catch (IllegalArgumentException e) {
      assertTrue("Unexpected exception", e.getMessage().contains("no argument constructor"));
      assertTrue("Unexpected exception", e.getMessage().contains(className));
    }
  }

  /** Test that a custom runner builder is loaded */
  @Test
  public void fromBundle_customRunnerBuilder() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_RUNNER_BUILDER, CustomRunnerBuilder.class.getName());
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals(
        "Mismatch in number of runner builders loaded", 1, args.runnerBuilderClasses.size());
    Class<? extends RunnerBuilder> runnerBuilderClass = args.runnerBuilderClasses.get(0);
    assertTrue(
        "RunnerBuilder not of correct type", runnerBuilderClass == CustomRunnerBuilder.class);
  }

  /** Test that a custom runner builder is loaded */
  @Test
  public void fromBundle_notRunnerBuilder() {
    Bundle b = new Bundle();
    String className = CustomTestFilter.class.getName();
    b.putString(RunnerArgs.ARGUMENT_RUNNER_BUILDER, className);
    try {
      new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
      fail("Did not detect invalid runner builder");
    } catch (IllegalArgumentException e) {
      assertEquals(
          "Unexpected exception",
          className + " does not extend " + RunnerBuilder.class.getName(),
          e.getMessage());
    }
  }

  @Test
  public void testFromBundle_allFieldsAreSupported() throws Exception {
    RunnerArgs defaultValues = new RunnerArgs.Builder().build();

    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_ANNOTATION, "annotation");
    b.putString(RunnerArgs.ARGUMENT_APP_LISTENER, AppLifecycleListener.class.getName());
    b.putString(RunnerArgs.ARGUMENT_COVERAGE, "true");
    b.putString(RunnerArgs.ARGUMENT_COVERAGE_PATH, "coveragePath");
    b.putString(RunnerArgs.ARGUMENT_DEBUG, "true");
    b.putString(RunnerArgs.ARGUMENT_DELAY_IN_MILLIS, "100");
    b.putString(RunnerArgs.ARGUMENT_DISABLE_ANALYTICS, "true");
    b.putString(RunnerArgs.ARGUMENT_LISTENER, RunListener.class.getName());
    b.putString(RunnerArgs.ARGUMENT_FILTER, CustomTestFilter.class.getName());
    b.putString(RunnerArgs.ARGUMENT_RUNNER_BUILDER, CustomRunnerBuilder.class.getName());
    b.putString(RunnerArgs.ARGUMENT_LOG_ONLY, "true");
    b.putString(RunnerArgs.ARGUMENT_NOT_ANNOTATION, "notAnnotation");
    b.putString(RunnerArgs.ARGUMENT_SHARD_INDEX, "1");
    b.putString(RunnerArgs.ARGUMENT_SUITE_ASSIGNMENT, "true");
    b.putString(RunnerArgs.ARGUMENT_TEST_CLASS, "test.Class");
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_CLASS, "test.NotClass");
    b.putString(RunnerArgs.ARGUMENT_TEST_PACKAGE, "test.package");
    b.putString(RunnerArgs.ARGUMENT_NOT_TEST_PACKAGE, "test.notpackage");
    b.putString(RunnerArgs.ARGUMENT_TEST_SIZE, "medium");
    b.putString(RunnerArgs.ARGUMENT_TIMEOUT, "100");
    b.putString(RunnerArgs.ARGUMENT_NUM_SHARDS, "2");
    b.putString(RunnerArgs.ARGUMENT_REMOTE_INIT_METHOD, "test.class#method");
    b.putString(RunnerArgs.ARGUMENT_TARGET_PROCESS, "con.foo.bar");
    b.putString(RunnerArgs.ARGUMENT_ORCHESTRATOR_SERVICE, "test.orchestratorService");
    b.putString(RunnerArgs.ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR, "true");
    b.putString(RunnerArgs.ARGUMENT_ORCHESTRATOR_DISCOVERY_SERVICE, "test.DiscoveryService");
    b.putString(RunnerArgs.ARGUMENT_ORCHESTRATOR_RUN_EVENTS_SERVICE, "test.RunEventsService");
    b.putString(RunnerArgs.ARGUMENT_IS_TEST_SERVICE_AVAILABLE, "true");
    b.putString(RunnerArgs.ARGUMENT_SHELL_EXEC_BINDER_KEY, "secret");
    b.putString(
        RunnerArgs.ARGUMENT_SCREENSHOT_PROCESSORS,
        "androidx.test.runner.screenshot.BasicScreenCaptureProcessor");
    b.putString(RunnerArgs.ARGUMENT_RUN_LISTENER_NEW_ORDER, "true");
    b.putString(RunnerArgs.ARGUMENT_CLASSPATH_TO_SCAN, "/foo/baz/f.dex:/foo/bar/f.dex");
    b.putString(RunnerArgs.ARGUMENT_TESTS_REGEX, "myregex");

    RunnerArgs fromBundle = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();

    Set<String> exceptions = new HashSet<>();
    // Parsing of testFile requires a real file on the disk, same for classloader leave those
    // ones out.
    exceptions.addAll(Arrays.asList("testFile", "classLoader"));

    for (Field field : RunnerArgs.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || exceptions.contains(field.getName())) {
        continue;
      }

      assertNotEquals(
          String.format("Field %s not set in fromBundle", field.getName()),
          field.get(defaultValues),
          field.get(fromBundle));
    }
  }

  /** Test parsing bundle when an invalid test timeout is provided */
  @Test(expected = NumberFormatException.class)
  @SuppressWarnings("TestExceptionChecker")
  public void testFromBundle_timeoutWithWrongFormat() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TIMEOUT, "not a long");
    new RunnerArgs.Builder().fromBundle(instrumentation, b);
  }

  /** Test parsing bundle when a negative test timeout is provided */
  @Test(expected = NumberFormatException.class)
  @SuppressWarnings("TestExceptionChecker")
  public void testFromBundle_timeoutWithNegativeValue() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TIMEOUT, "-500");
    new RunnerArgs.Builder().fromBundle(instrumentation, b);
  }

  /** Test parsing the boolean idle argument */
  @Test
  public void testFromBundle_targetProcess() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_TARGET_PROCESS, "com.foo.bar");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals("com.foo.bar", args.targetProcess);

    b.putString(RunnerArgs.ARGUMENT_TARGET_PROCESS, "com.foo.bar:ui");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertEquals("com.foo.bar:ui", args.targetProcess);
  }

  /** Test parsing the boolean legacyRunListenerMode argument */
  @Test
  public void testFromBundle_legacyRunListenerMode() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_RUN_LISTENER_NEW_ORDER, "true");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertTrue(args.newRunListenerMode);

    b.putString(RunnerArgs.ARGUMENT_RUN_LISTENER_NEW_ORDER, "false");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.newRunListenerMode);

    b.putString(RunnerArgs.ARGUMENT_RUN_LISTENER_NEW_ORDER, "blargh");
    args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.newRunListenerMode);
  }

  @Test
  public void testFromBundle_isTestStorageAvailable_argNotPresent() {
    Bundle b = new Bundle();
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.isTestServiceAvailable);
  }

  @Test
  public void testFromBundle_isTestStorageAvailable_falseValue() {
    Bundle b = new Bundle();
    b.putString(RunnerArgs.ARGUMENT_IS_TEST_SERVICE_AVAILABLE, "false");
    RunnerArgs args = new RunnerArgs.Builder().fromBundle(instrumentation, b).build();
    assertFalse(args.isTestServiceAvailable);
  }
}
