/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

package androidx.test.internal.runner;

import android.app.Instrumentation;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import androidx.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;
import androidx.test.runner.lifecycle.ApplicationLifecycleCallback;
import androidx.test.runner.screenshot.ScreenCaptureProcessor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.RunnerBuilder;

/** Contains input arguments passed to the instrumentation test runner. */
public class RunnerArgs {
  private static final String LOG_TAG = "RunnerArgs";

  // constants for supported instrumentation arguments
  static final String ARGUMENT_TEST_CLASS = "class";
  static final String ARGUMENT_CLASSPATH_TO_SCAN = "classpathToScan";
  static final String ARGUMENT_NOT_TEST_CLASS = "notClass";
  static final String ARGUMENT_TEST_SIZE = "size";
  static final String ARGUMENT_LOG_ONLY = "log";
  static final String ARGUMENT_ANNOTATION = "annotation";
  static final String ARGUMENT_NOT_ANNOTATION = "notAnnotation";
  static final String ARGUMENT_NUM_SHARDS = "numShards";
  static final String ARGUMENT_SHARD_INDEX = "shardIndex";
  static final String ARGUMENT_DELAY_IN_MILLIS = "delay_msec";
  static final String ARGUMENT_COVERAGE = "coverage";
  static final String ARGUMENT_COVERAGE_PATH = "coverageFile";
  static final String ARGUMENT_SUITE_ASSIGNMENT = "suiteAssignment";
  static final String ARGUMENT_DEBUG = "debug";
  static final String ARGUMENT_LISTENER = "listener";
  static final String ARGUMENT_FILTER = "filter";
  static final String ARGUMENT_RUNNER_BUILDER = "runnerBuilder";
  static final String ARGUMENT_TEST_PACKAGE = "package";
  static final String ARGUMENT_NOT_TEST_PACKAGE = "notPackage";
  static final String ARGUMENT_TIMEOUT = "timeout_msec";
  static final String ARGUMENT_TEST_FILE = "testFile";
  static final String ARGUMENT_NOT_TEST_FILE = "notTestFile";
  static final String ARGUMENT_DISABLE_ANALYTICS = "disableAnalytics";
  static final String ARGUMENT_APP_LISTENER = "appListener";
  static final String ARGUMENT_CLASS_LOADER = "classLoader";
  static final String ARGUMENT_REMOTE_INIT_METHOD = "remoteMethod";
  static final String ARGUMENT_TARGET_PROCESS = "targetProcess";
  static final String ARGUMENT_SCREENSHOT_PROCESSORS = "screenCaptureProcessors";
  static final String ARGUMENT_ORCHESTRATOR_SERVICE = "orchestratorService";
  static final String ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR = "listTestsForOrchestrator";
  static final String ARGUMENT_ORCHESTRATOR_DISCOVERY_SERVICE = "testDiscoveryService";
  static final String ARGUMENT_ORCHESTRATOR_RUN_EVENTS_SERVICE = "testRunEventsService";
  static final String ARGUMENT_IS_TEST_SERVICE_AVAILABLE = "isTestServiceAvailable";
  static final String ARGUMENT_SHELL_EXEC_BINDER_KEY = "shellExecBinderKey";
  static final String ARGUMENT_RUN_LISTENER_NEW_ORDER = "newRunListenerMode";
  static final String ARGUMENT_TESTS_REGEX = "tests_regex";

  // used to separate multiple fully-qualified test case class names
  private static final String CLASS_SEPARATOR = ",";
  // used to separate classpath entries
  private static final String CLASSPATH_SEPARATOR = ":";
  // used to separate fully-qualified test case class name, and one of its methods
  private static final char METHOD_SEPARATOR = '#';

  public final boolean debug;
  public final boolean suiteAssignment;
  public final boolean codeCoverage;
  public final String codeCoveragePath;
  public final int delayInMillis;
  public final boolean logOnly;
  public final List<String> testPackages;
  public final List<String> notTestPackages;
  public final String testSize;
  public final List<String> annotations;
  public final List<String> notAnnotations;
  public final long testTimeout;
  public final List<RunListener> listeners;
  public final List<Filter> filters;
  public final List<Class<? extends RunnerBuilder>> runnerBuilderClasses;
  public final List<TestArg> tests;
  public final List<TestArg> notTests;
  public final int numShards;
  public final int shardIndex;
  public final boolean disableAnalytics;
  public final List<ApplicationLifecycleCallback> appListeners;
  public final ClassLoader classLoader;
  public final Set<String> classpathToScan;
  public final TestArg remoteMethod;
  public final String targetProcess;
  public final List<ScreenCaptureProcessor> screenCaptureProcessors;
  public final String orchestratorService;
  public final boolean listTestsForOrchestrator;
  public final String testDiscoveryService;
  public final String testRunEventsService;
  public final boolean isTestServiceAvailable;
  public final String shellExecBinderKey;
  public final boolean newRunListenerMode;
  public final String testsRegEx;

  /** Encapsulates a test class and optional method. */
  public static class TestArg {
    public final String testClassName;
    public final String methodName;

    TestArg(String className, String methodName) {
      this.testClassName = className;
      this.methodName = methodName;
    }

    TestArg(String className) {
      this(className, null);
    }

    @Override
    public String toString() {
      return methodName != null ? testClassName + METHOD_SEPARATOR + methodName : testClassName;
    }
  }

  /** Encapsulates a list of test args and a list of package args found in a test file. */
  private static final class TestFileArgs {
    private final List<TestArg> tests = new ArrayList<>();
    private final List<String> packages = new ArrayList<>();
  }

  private RunnerArgs(Builder builder) {
    this.debug = builder.debug;
    this.suiteAssignment = builder.suiteAssignment;
    this.codeCoverage = builder.codeCoverage;
    this.codeCoveragePath = builder.codeCoveragePath;
    this.delayInMillis = builder.delayInMillis;
    this.logOnly = builder.logOnly;
    this.testPackages = builder.testPackages;
    this.notTestPackages = builder.notTestPackages;
    this.testSize = builder.testSize;
    this.annotations = Collections.unmodifiableList(builder.annotations);
    this.notAnnotations = Collections.unmodifiableList(builder.notAnnotations);
    this.testTimeout = builder.testTimeout;
    this.listeners = Collections.unmodifiableList(builder.listeners);
    this.filters = Collections.unmodifiableList(builder.filters);
    this.runnerBuilderClasses = Collections.unmodifiableList(builder.runnerBuilderClasses);
    this.tests = Collections.unmodifiableList(builder.tests);
    this.notTests = Collections.unmodifiableList(builder.notTests);
    this.numShards = builder.numShards;
    this.shardIndex = builder.shardIndex;
    this.disableAnalytics = builder.disableAnalytics;
    this.appListeners = Collections.unmodifiableList(builder.appListeners);
    this.classLoader = builder.classLoader;
    this.classpathToScan = builder.classpathToScan;
    this.remoteMethod = builder.remoteMethod;
    this.orchestratorService = builder.orchestratorService;
    this.listTestsForOrchestrator = builder.listTestsForOrchestrator;
    this.testDiscoveryService = builder.testDiscoveryService;
    this.testRunEventsService = builder.testRunEventsService;
    this.isTestServiceAvailable = builder.isTestServiceAvailable;
    this.screenCaptureProcessors = Collections.unmodifiableList(builder.screenCaptureProcessors);
    this.targetProcess = builder.targetProcess;
    this.shellExecBinderKey = builder.shellExecBinderKey;
    this.newRunListenerMode = builder.newRunListenerMode;
    this.testsRegEx = builder.testsRegEx;
  }

  public static class Builder {
    private boolean debug = false;
    private boolean suiteAssignment = false;
    private boolean codeCoverage = false;
    private String codeCoveragePath = null;
    private int delayInMillis = -1;
    private boolean logOnly = false;
    private List<String> testPackages = new ArrayList<>();
    private List<String> notTestPackages = new ArrayList<>();
    private String testSize = null;
    private final List<String> annotations = new ArrayList<>();
    private final List<String> notAnnotations = new ArrayList<>();
    private long testTimeout = -1;
    private List<RunListener> listeners = new ArrayList<RunListener>();
    private List<Filter> filters = new ArrayList<>();
    private List<Class<? extends RunnerBuilder>> runnerBuilderClasses = new ArrayList<>();
    private List<TestArg> tests = new ArrayList<>();
    private List<TestArg> notTests = new ArrayList<>();
    private int numShards = 0;
    private int shardIndex = 0;
    private boolean disableAnalytics = false;
    private List<ApplicationLifecycleCallback> appListeners =
        new ArrayList<ApplicationLifecycleCallback>();
    private ClassLoader classLoader = null;
    private Set<String> classpathToScan = new HashSet<>();
    private TestArg remoteMethod = null;
    private String orchestratorService = null;
    private boolean listTestsForOrchestrator = false;
    private String testDiscoveryService = null;
    private String testRunEventsService = null;
    private boolean isTestServiceAvailable = false;
    private String targetProcess = null;
    private List<ScreenCaptureProcessor> screenCaptureProcessors = new ArrayList<>();
    public String shellExecBinderKey;
    private boolean newRunListenerMode = false;
    private String testsRegEx = null;

    /**
     * Populate the arg data from the given Bundle.
     *
     * <p>Note: This will override any manifest-provided args
     */
    public Builder fromBundle(Instrumentation instr, Bundle bundle) {
      this.debug = parseBoolean(bundle.getString(ARGUMENT_DEBUG));
      this.delayInMillis =
          parseUnsignedInt(bundle.get(ARGUMENT_DELAY_IN_MILLIS), ARGUMENT_DELAY_IN_MILLIS);
      // parse test class args
      this.tests.addAll(parseTestClasses(bundle.getString(ARGUMENT_TEST_CLASS)));
      this.notTests.addAll(parseTestClasses(bundle.getString(ARGUMENT_NOT_TEST_CLASS)));
      // parse test package args
      this.testPackages.addAll(parseTestPackages(bundle.getString(ARGUMENT_TEST_PACKAGE)));
      this.notTestPackages.addAll(parseTestPackages(bundle.getString(ARGUMENT_NOT_TEST_PACKAGE)));
      // parse test file args, which may include class and package args
      TestFileArgs testFileArgs = parseFromFile(instr, bundle.getString(ARGUMENT_TEST_FILE));
      this.tests.addAll(testFileArgs.tests);
      this.testPackages.addAll(testFileArgs.packages);
      TestFileArgs notTestFileArgs = parseFromFile(instr, bundle.getString(ARGUMENT_NOT_TEST_FILE));
      this.notTests.addAll(notTestFileArgs.tests);
      this.notTestPackages.addAll(notTestFileArgs.packages);
      this.listeners.addAll(
          parseLoadAndInstantiateClasses(
              bundle.getString(ARGUMENT_LISTENER), RunListener.class, null));
      this.filters.addAll(
          parseLoadAndInstantiateClasses(bundle.getString(ARGUMENT_FILTER), Filter.class, bundle));
      this.runnerBuilderClasses.addAll(
          parseAndLoadClasses(bundle.getString(ARGUMENT_RUNNER_BUILDER), RunnerBuilder.class));
      this.testSize = bundle.getString(ARGUMENT_TEST_SIZE);
      this.annotations.addAll(parseStrings(bundle.getString(ARGUMENT_ANNOTATION)));
      this.notAnnotations.addAll(parseStrings(bundle.getString(ARGUMENT_NOT_ANNOTATION)));
      this.testTimeout = parseUnsignedLong(bundle.getString(ARGUMENT_TIMEOUT), ARGUMENT_TIMEOUT);
      this.numShards = parseUnsignedInt(bundle.get(ARGUMENT_NUM_SHARDS), ARGUMENT_NUM_SHARDS);
      this.shardIndex = parseUnsignedInt(bundle.get(ARGUMENT_SHARD_INDEX), ARGUMENT_SHARD_INDEX);
      this.logOnly = parseBoolean(bundle.getString(ARGUMENT_LOG_ONLY));
      this.disableAnalytics = parseBoolean(bundle.getString(ARGUMENT_DISABLE_ANALYTICS));
      this.appListeners.addAll(
          parseLoadAndInstantiateClasses(
              bundle.getString(ARGUMENT_APP_LISTENER), ApplicationLifecycleCallback.class, null));
      this.codeCoverage = parseBoolean(bundle.getString(ARGUMENT_COVERAGE));
      this.codeCoveragePath = bundle.getString(ARGUMENT_COVERAGE_PATH);
      this.suiteAssignment = parseBoolean(bundle.getString(ARGUMENT_SUITE_ASSIGNMENT));
      this.classLoader =
          parseLoadAndInstantiateClass(bundle.getString(ARGUMENT_CLASS_LOADER), ClassLoader.class);
      this.classpathToScan = parseClasspath(bundle.getString(ARGUMENT_CLASSPATH_TO_SCAN));
      if (bundle.containsKey(ARGUMENT_REMOTE_INIT_METHOD)) {
        this.remoteMethod = parseTestClass(bundle.getString(ARGUMENT_REMOTE_INIT_METHOD));
      }
      this.orchestratorService = bundle.getString(ARGUMENT_ORCHESTRATOR_SERVICE);
      this.listTestsForOrchestrator =
          parseBoolean(bundle.getString(ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR));
      this.testDiscoveryService = bundle.getString(ARGUMENT_ORCHESTRATOR_DISCOVERY_SERVICE);
      this.testRunEventsService = bundle.getString(ARGUMENT_ORCHESTRATOR_RUN_EVENTS_SERVICE);
      this.isTestServiceAvailable =
          parseBoolean(bundle.getString(ARGUMENT_IS_TEST_SERVICE_AVAILABLE));
      this.targetProcess = bundle.getString(ARGUMENT_TARGET_PROCESS);
      this.screenCaptureProcessors.addAll(
          parseLoadAndInstantiateClasses(
              bundle.getString(ARGUMENT_SCREENSHOT_PROCESSORS),
              ScreenCaptureProcessor.class,
              null));
      this.shellExecBinderKey = bundle.getString(ARGUMENT_SHELL_EXEC_BINDER_KEY);
      this.newRunListenerMode = parseBoolean(bundle.getString(ARGUMENT_RUN_LISTENER_NEW_ORDER));
      this.testsRegEx = bundle.getString(ARGUMENT_TESTS_REGEX);
      return this;
    }

    /** Populate the arg data from the instrumentation:metadata attribute in Manifest. */
    public Builder fromManifest(Instrumentation instr) {
      PackageManager pm = instr.getContext().getPackageManager();
      try {
        InstrumentationInfo instrInfo =
            pm.getInstrumentationInfo(instr.getComponentName(), PackageManager.GET_META_DATA);
        Bundle b = instrInfo.metaData;
        if (b == null) {
          // metadata not present - skip
          return this;
        }
        // parse the metadata using same key names
        return fromBundle(instr, b);
      } catch (PackageManager.NameNotFoundException e) {
        // should never happen
        Log.wtf(LOG_TAG, String.format("Could not find component %s", instr.getComponentName()));
      }
      return this;
    }

    /**
     * Utility method to split String element data in CSV format into a List.
     *
     * @return empty list if null input, otherwise list of strings
     */
    private static List<String> parseStrings(String value) {
      if (value == null) {
        return Collections.emptyList();
      }
      return Arrays.asList(value.split(","));
    }

    /**
     * Parse boolean value from a String.
     *
     * @return the boolean value, false on null input
     */
    private static boolean parseBoolean(String booleanValue) {
      return booleanValue != null && Boolean.parseBoolean(booleanValue);
    }

    /**
     * Parse int from given value - except either int or string.
     *
     * @return the value, -1 if not found
     * @throws NumberFormatException if value is negative or not a number
     */
    private static int parseUnsignedInt(Object value, String name) {
      if (value != null) {
        int intValue = Integer.parseInt(value.toString());
        if (intValue < 0) {
          throw new NumberFormatException(name + " can not be negative");
        }

        return intValue;
      }
      return -1;
    }

    /**
     * Parse long from given value - except either Long or String.
     *
     * @return the value, -1 if not found
     * @throws NumberFormatException if value is negative or not a number
     */
    private static long parseUnsignedLong(Object value, String name) {
      if (value != null) {
        long longValue = Long.parseLong(value.toString());
        if (longValue < 0) {
          throw new NumberFormatException(name + " can not be negative");
        }
        return longValue;
      }
      return -1;
    }

    /**
     * Parse test package data from given CSV data in the following format:
     * com.android.foo,com.android.bar,...
     *
     * @return list of package names, empty list if input is null
     */
    private static List<String> parseTestPackages(String packagesArg) {
      List<String> packages = new ArrayList<>();
      if (packagesArg != null) {
        for (String packageName : packagesArg.split(CLASS_SEPARATOR)) {
          packages.add(packageName);
        }
      }
      return packages;
    }

    /**
     * Parse test class and method data from given CSV data in following format:
     * com.TestClass1#method1,com.TestClass2,...
     *
     * @return list of {@link TestArg} data, empty list if input is null
     */
    private List<TestArg> parseTestClasses(String classesArg) {
      List<TestArg> tests = new ArrayList<TestArg>();
      if (classesArg != null) {
        for (String className : classesArg.split(CLASS_SEPARATOR)) {
          tests.add(parseTestClass(className));
        }
      }
      return tests;
    }

    /**
     * Parse classpath in the following format: {@code
     * /foo/class1.dex:/foo/class2.dex:/bar/class1.dex:...}
     *
     * @param classpath
     * @return {@link Set} of paths, empty list if input is {@code null}
     */
    private static Set<String> parseClasspath(String classpath) {
      if (classpath == null || classpath.isEmpty()) {
        return new HashSet<>();
      }
      return new HashSet<>(Arrays.asList(classpath.split(CLASSPATH_SEPARATOR, -1)));
    }

    /**
     * Parse an individual test class and optionally method from given string.
     *
     * <p>Expected format: com.TestClass1[#method1]
     */
    private static TestArg parseTestClass(String testClassName) {
      if (TextUtils.isEmpty(testClassName)) {
        return null;
      }
      int methodSeparatorIndex = testClassName.indexOf(METHOD_SEPARATOR);
      if (methodSeparatorIndex > 0) {
        String testMethodName = testClassName.substring(methodSeparatorIndex + 1);
        testClassName = testClassName.substring(0, methodSeparatorIndex);
        return new TestArg(testClassName, testMethodName);
      } else {
        return new TestArg(testClassName);
      }
    }

    /**
     * Parse and load the packages, classes and methods of a test file.
     *
     * @param instr instrumentation handle.
     * @param filePath path to test file containing package names, full package names of test
     *     classes and optionally methods to add.
     */
    private TestFileArgs parseFromFile(Instrumentation instr, String filePath) {
      final TestFileArgs args = new TestFileArgs();
      if (filePath == null) {
        return args;
      }
      BufferedReader reader = null;
      try {
        reader = openFile(instr, filePath);
        String line;
        while ((line = reader.readLine()) != null) {
          if (isClassOrMethod(line)) {
            args.tests.add(parseTestClass(line));
          } else {
            // validate and parse test package
            args.packages.addAll(parseTestPackages(line));
          }
        }
      } catch (FileNotFoundException e) {
        throw new IllegalArgumentException("testfile not found: " + filePath, e);
      } catch (IOException e) {
        throw new IllegalArgumentException("Could not read test file " + filePath, e);
      } finally {
        if (reader != null) {
          try {
            reader.close();
          } catch (IOException e) {
            /* ignore */
          }
        }
      }
      return args;
    }

    private BufferedReader openFile(Instrumentation instr, String filePath) throws IOException {
      // If we are running as an instant app, then read the file through the shell
      // since the APK is in the targetSandboxVersion="2" with restrictive SELinux
      // policy which prevents reading from /data/local.
      final boolean isInstantApp =
          Build.VERSION.SDK_INT >= 26 && instr.getContext().getPackageManager().isInstantApp();
      return new BufferedReader(
          isInstantApp
              ? new InputStreamReader(
                  new AutoCloseInputStream(
                      instr.getUiAutomation().executeShellCommand("cat " + filePath)))
              : new FileReader(new File(filePath)));
    }

    /**
     * Determine whether line from test file represents a test class or method, as opposed to
     * package name.
     *
     * @param line string containing either an individual test class/method or a package name
     * @return true if line contains an individual test class or method
     */
    @VisibleForTesting
    static boolean isClassOrMethod(String line) {
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (c == '#' || Character.isUpperCase(c)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Create a set of objects given a CSV string of full class names and type.
     *
     * @return the List of objects or empty list on null input
     */
    private <T> List<T> parseLoadAndInstantiateClasses(
        String classString, Class<T> type, Bundle bundle) {
      List<T> objects = new ArrayList<T>();
      if (classString != null) {
        for (String className : classString.split(CLASS_SEPARATOR)) {
          loadClassByNameInstantiateAndAdd(objects, className, type, bundle);
        }
      }
      return objects;
    }

    /**
     * Create an object of the given full class name.
     *
     * @return the object instance or null on null input
     */
    private <T> T parseLoadAndInstantiateClass(String classString, Class<T> type) {
      List<T> classLoaders = parseLoadAndInstantiateClasses(classString, type, null);
      if (!classLoaders.isEmpty()) {
        if (classLoaders.size() > 1) {
          throw new IllegalArgumentException(
              String.format("Expected 1 class loader, %d given", classLoaders.size()));
        }
        return classLoaders.get(0);
      }
      return null;
    }

    /**
     * Load class by supplied name, instantiate and add object to supplied list.
     *
     * <p>No effect if input is null or empty.
     *
     * @param objects the List to add to
     * @param className the fully qualified class name
     * @param bundle The bundle to pass to the constructor, null if no bundle is to be passed.
     * @throws IllegalArgumentException if listener cannot be loaded
     */
    private <T> void loadClassByNameInstantiateAndAdd(
        List<T> objects, String className, Class<T> type, Bundle bundle) {
      if (className == null || className.length() == 0) {
        return;
      }
      try {
        @SuppressWarnings("unchecked")
        final Class<? extends T> klass = (Class<? extends T>) Class.forName(className);
        Constructor<? extends T> constructor;
        Object[] arguments;

        // Look for the default constructor first to ensure backwards compatibility with
        // previous code.
        try {
          constructor = klass.getConstructor();
          arguments = new Object[0];
        } catch (NoSuchMethodException nsme1) {
          // Cannot find a default constructor so if a bundle is supplied then look for
          // one that takes a Bundle.
          if (bundle != null) {
            try {
              constructor = klass.getConstructor(Bundle.class);
              arguments = new Object[] {bundle};
            } catch (NoSuchMethodException nsme2) {
              // Could not find a constructor that takes a bundle so rethrow the
              // original exception, remembering to record that this exception was
              // suppressed.
              nsme2.initCause(nsme1);
              throw nsme2;
            }
          } else {
            // Rethrow exception as no bundle was provided.
            throw nsme1;
          }
        }
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        final T instance = constructor.newInstance(arguments);
        objects.add(instance);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException("Could not find extra class " + className);
      } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException(
            "Must have no argument constructor for class " + className);
      } catch (ClassCastException e) {
        throw new IllegalArgumentException(className + " does not extend " + type.getName());
      } catch (InstantiationException e) {
        throw new IllegalArgumentException("Failed to create: " + className, e);
      } catch (InvocationTargetException e) {
        throw new IllegalArgumentException("Failed to create: " + className, e);
      } catch (IllegalAccessException e) {
        throw new IllegalArgumentException("Failed to create listener: " + className, e);
      }
    }

    /**
     * Create a set of classes given a CSV string of full class names and type.
     *
     * @return the List of classes or empty list on null input
     */
    private <T> List<Class<? extends T>> parseAndLoadClasses(String classString, Class<T> type) {
      List<Class<? extends T>> classes = new ArrayList<>();
      if (classString != null) {
        for (String className : classString.split(CLASS_SEPARATOR)) {
          loadClassByNameAndAdd(classes, className, type);
        }
      }
      return classes;
    }

    /**
     * Load class by supplied name and add to the supplied list.
     *
     * <p>No effect if input is null or empty.
     *
     * @param classes the List to add to
     * @param type the required ancestor of the class
     * @param className the fully qualified class name
     * @throws IllegalArgumentException if listener cannot be loaded
     */
    private <T> void loadClassByNameAndAdd(
        List<Class<? extends T>> classes, String className, Class<T> type) {
      if (null == className || className.length() == 0) {
        return;
      }
      try {
        Class<?> klass = Class.forName(className);
        if (!type.isAssignableFrom(klass)) {
          throw new IllegalArgumentException(className + " does not extend " + type.getName());
        }
        @SuppressWarnings("unchecked")
        Class<? extends T> castClass = (Class<? extends T>) klass;
        classes.add(castClass);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException("Could not find extra class " + className);
      } catch (ClassCastException e) {
        throw new IllegalArgumentException(className + " does not extend " + type.getName());
      }
    }

    public RunnerArgs build() {
      return new RunnerArgs(this);
    }
  }
}
