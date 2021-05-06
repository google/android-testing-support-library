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

import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import androidx.test.filters.RequiresDevice;
import androidx.test.filters.SdkSuppress;
import androidx.test.internal.runner.ClassPathScanner.ChainedClassNameFilter;
import androidx.test.internal.runner.ClassPathScanner.ExcludeClassNamesFilter;
import androidx.test.internal.runner.ClassPathScanner.ExcludePackageNameFilter;
import androidx.test.internal.runner.ClassPathScanner.ExternalClassNameFilter;
import androidx.test.internal.runner.ClassPathScanner.InclusivePackageNamesFilter;
import androidx.test.internal.runner.filters.ParentFilter;
import androidx.test.internal.runner.filters.TestsRegExFilter;
import androidx.test.internal.util.AndroidRunnerParams;
import androidx.test.internal.util.Checks;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.runner.Description;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.Suite;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

/**
 * A builder for {@link Request} that builds up tests to run, filtered on provided set of
 * restrictions.
 */
public class TestRequestBuilder {
  private static final String TAG = "TestRequestBuilder";

  // Excluded test packages
  private static final String[] DEFAULT_EXCLUDED_PACKAGES = {
    "junit",
    "org.junit",
    "org.hamcrest",
    "org.mockito", // exclude Mockito for performance and to prevent JVM related errors
    "androidx.test.internal.runner.junit3", // always skip AndroidTestSuite
    "org.jacoco", // exclude Jacoco to prevent class loading issues
    "net.bytebuddy" // exclude byte buddy to prevent Mockito 2.0 class loading issues
  };

  static final String MISSING_ARGUMENTS_MSG =
      "Must provide either classes to run, or paths to scan";
  static final String AMBIGUOUS_ARGUMENTS_MSG =
      "Ambiguous arguments: cannot provide both test package and test class(es) to run";

  private final List<String> pathsToScan = new ArrayList<>();
  private Set<String> includedPackages = new HashSet<>();
  private Set<String> excludedPackages = new HashSet<>();
  private Set<String> includedClasses = new HashSet<>();
  private Set<String> excludedClasses = new HashSet<>();
  private ClassAndMethodFilter classMethodFilter = new ClassAndMethodFilter();
  private final TestsRegExFilter testsRegExFilter = new TestsRegExFilter();
  private Filter filter =
      new AnnotationExclusionFilter(androidx.test.filters.Suppress.class)
          .intersect(new SdkSuppressFilter())
          .intersect(new RequiresDeviceFilter())
          .intersect(classMethodFilter)
          .intersect(testsRegExFilter);
  private List<Class<? extends RunnerBuilder>> customRunnerBuilderClasses = new ArrayList<>();
  private boolean skipExecution = false;
  private final DeviceBuild deviceBuild;
  private long perTestTimeout = 0;
  private final Instrumentation instr;
  private final Bundle argsBundle;
  private ClassLoader classLoader;

  /**
   * Instructs the test builder if JUnit3 suite() methods should be executed.
   *
   * <p>Currently set to false if any method filter is set, for consistency with
   * InstrumentationTestRunner.
   */
  private boolean ignoreSuiteMethods = false;

  /**
   * Accessor interface for retrieving device build properties.
   *
   * <p>Used so unit tests can mock calls
   */
  interface DeviceBuild {
    /** Returns the SDK API level for current device. */
    int getSdkVersionInt();

    /** Returns the hardware type of the current device. */
    String getHardware();

    /** Returns the version code name of the current device. */
    String getCodeName();
  }

  private static class DeviceBuildImpl implements DeviceBuild {
    @Override
    public int getSdkVersionInt() {
      return android.os.Build.VERSION.SDK_INT + ("REL".equals(Build.VERSION.CODENAME) ? 0 : 1);
    }

    @Override
    public String getHardware() {
      return android.os.Build.HARDWARE;
    }

    @Override
    public String getCodeName() {
      return android.os.Build.VERSION.CODENAME;
    }
  }

  /** Filter that only runs tests whose method or class has been annotated with given filter. */
  private static class AnnotationInclusionFilter extends ParentFilter {

    private final Class<? extends Annotation> annotationClass;

    AnnotationInclusionFilter(Class<? extends Annotation> annotation) {
      annotationClass = annotation;
    }

    /**
     * Determine if given test description matches filter.
     *
     * @param description the {@link Description} describing the test
     * @return <code>true</code> if matched
     */
    @Override
    protected boolean evaluateTest(Description description) {
      final Class<?> testClass = description.getTestClass();
      return description.getAnnotation(annotationClass) != null
          || (testClass != null && testClass.isAnnotationPresent(annotationClass));
    }

    protected Class<? extends Annotation> getAnnotationClass() {
      return annotationClass;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
      return String.format("annotation %s", annotationClass.getName());
    }
  }

  /**
   * A filter for test sizes.
   *
   * <p>Will match if test method has given size annotation, or class does, but only if method does
   * not have any other size annotations. ie method size annotation overrides class size annotation.
   */
  private static class SizeFilter extends ParentFilter {

    private final TestSize testSize;

    SizeFilter(TestSize testSize) {
      this.testSize = testSize;
    }

    @Override
    public String describe() {
      return "";
    }

    @Override
    protected boolean evaluateTest(Description description) {
      // If test method is annotated with test size annotation include it
      if (testSize.testMethodIsAnnotatedWithTestSize(description)) {
        return true;
      } else if (testSize.testClassIsAnnotatedWithTestSize(description)) {
        // size annotation matched at class level. Make sure method doesn't have any other
        // size annotations
        for (Annotation a : description.getAnnotations()) {
          if (TestSize.isAnyTestSize(a.annotationType())) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
  }

  /** Filter out tests whose method or class has been annotated with given filter. */
  private static class AnnotationExclusionFilter extends ParentFilter {

    private final Class<? extends Annotation> annotationClass;

    AnnotationExclusionFilter(Class<? extends Annotation> annotation) {
      annotationClass = annotation;
    }

    @Override
    protected boolean evaluateTest(Description description) {
      final Class<?> testClass = description.getTestClass();
      if ((testClass != null && testClass.isAnnotationPresent(annotationClass))
          || (description.getAnnotation(annotationClass) != null)) {
        return false;
      }
      return true;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
      return String.format("not annotation %s", annotationClass.getName());
    }
  }

  private static class ExtendedSuite extends Suite {

    static Suite createSuite(List<Runner> runners) {
      try {
        return new ExtendedSuite(runners);
      } catch (InitializationError e) {
        throw new RuntimeException(
            "Internal Error: "
                + Suite.class.getName()
                + "(Class<?>, List<Runner>) should never throw an "
                + "InitializationError when passed a null Class");
      }
    }

    ExtendedSuite(List<Runner> runners) throws InitializationError {
      super(null, runners);
    }
  }

  private class SdkSuppressFilter extends ParentFilter {

    @Override
    protected boolean evaluateTest(Description description) {
      final SdkSuppress sdkSuppress = getAnnotationForTest(description);
      if (sdkSuppress != null) {
        if ((getDeviceSdkInt() >= sdkSuppress.minSdkVersion()
                && getDeviceSdkInt() <= sdkSuppress.maxSdkVersion())
            || getDeviceCodeName().equals(sdkSuppress.codeName())) {
          return true; // run the test
        }
        return false; // don't run the test
      }
      return true; // no SdkSuppress, run the test
    }

    private SdkSuppress getAnnotationForTest(Description description) {
      final SdkSuppress s = description.getAnnotation(SdkSuppress.class);
      if (s != null) {
        return s;
      }
      final Class<?> testClass = description.getTestClass();
      if (testClass != null) {
        return testClass.getAnnotation(SdkSuppress.class);
      }
      return null;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
      return String.format("skip tests annotated with SdkSuppress if necessary");
    }
  }

  /** Class that filters out tests annotated with {@link RequiresDevice} when running on emulator */
  @VisibleForTesting
  class RequiresDeviceFilter extends AnnotationExclusionFilter {

    static final String EMULATOR_HARDWARE_GOLDFISH = "goldfish";
    static final String EMULATOR_HARDWARE_RANCHU = "ranchu";
    // TODO(b/65053549) Remove once we have a more generic solution
    static final String EMULATOR_HARDWARE_GCE = "gce_x86";

    private final Set<String> emulatorHardwareNames =
        new HashSet<>(
            Arrays.asList(
                EMULATOR_HARDWARE_GOLDFISH, EMULATOR_HARDWARE_RANCHU, EMULATOR_HARDWARE_GCE));

    RequiresDeviceFilter() {
      super(RequiresDevice.class);
    }

    @Override
    protected boolean evaluateTest(Description description) {
      if (!super.evaluateTest(description)) {
        // annotation is present - check if device is an emulator
        return !emulatorHardwareNames.contains(getDeviceHardware());
      }
      return true;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
      return String.format("skip tests annotated with RequiresDevice if necessary");
    }
  }

  private static class ShardingFilter extends Filter {
    private final int numShards;
    private final int shardIndex;

    ShardingFilter(int numShards, int shardIndex) {
      this.numShards = numShards;
      this.shardIndex = shardIndex;
    }

    @Override
    public boolean shouldRun(Description description) {
      if (description.isTest()) {
        return (Math.abs(description.hashCode()) % numShards) == shardIndex;
      }

      // The description is a suite, so assume that it can be run so that filtering is
      // applied to its children. If after filtering it has no children then it will be
      // automatically filtered out.
      return true;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
      return String.format("Shard %s of %s shards", shardIndex, numShards);
    }
  }

  /**
   * A {@link Request} that doesn't report an error if all tests are filtered out. Done for
   * consistency with InstrumentationTestRunner.
   */
  private static class LenientFilterRequest extends Request {
    private final Request request;
    private final Filter filter;

    public LenientFilterRequest(Request classRequest, Filter filter) {
      request = classRequest;
      this.filter = filter;
    }

    @Override
    public Runner getRunner() {
      try {
        Runner runner = request.getRunner();
        filter.apply(runner);
        return runner;
      } catch (NoTestsRemainException e) {
        // don't treat filtering out all tests as an error
        return new BlankRunner();
      }
    }
  }

  /** A {@link Runner} that doesn't do anything */
  private static class BlankRunner extends Runner {
    @Override
    public Description getDescription() {
      return Description.createSuiteDescription("no tests found");
    }

    @Override
    public void run(RunNotifier notifier) {
      // do nothing
    }
  }

  /** A {@link Filter} to support the ability to filter out multiple class#method combinations. */
  private static class ClassAndMethodFilter extends ParentFilter {

    private Map<String, MethodFilter> methodFilters = new HashMap<>();

    @Override
    public boolean evaluateTest(Description description) {
      if (methodFilters.isEmpty()) {
        return true;
      }
      String className = description.getClassName();
      MethodFilter methodFilter = methodFilters.get(className);
      if (methodFilter != null) {
        return methodFilter.shouldRun(description);
      }
      // This test class was not explicitly excluded and none of it's test methods were
      // explicitly included or excluded. Should be run, return true:
      return true;
    }

    @Override
    public String describe() {
      return "Class and method filter";
    }

    public void addMethod(String className, String methodName) {
      MethodFilter methodFilter = methodFilters.get(className);
      if (methodFilter == null) {
        methodFilter = new MethodFilter(className);
        methodFilters.put(className, methodFilter);
      }
      methodFilter.addInclusionMethod(methodName);
    }

    public void removeMethod(String className, String methodName) {
      MethodFilter methodFilter = methodFilters.get(className);
      if (methodFilter == null) {
        methodFilter = new MethodFilter(className);
        methodFilters.put(className, methodFilter);
      }
      methodFilter.addExclusionMethod(methodName);
    }
  }

  /** A {@link Filter} used to filter out desired test methods from a given class */
  private static class MethodFilter extends ParentFilter {

    private final String className;
    private Set<String> includedMethods = new HashSet<>();
    private Set<String> excludedMethods = new HashSet<>();

    /**
     * Constructs a method filter for a given class
     *
     * @param className name of the class the method belongs to
     */
    public MethodFilter(String className) {
      this.className = className;
    }

    @Override
    public String describe() {
      return "Method filter for " + className + " class";
    }

    @Override
    public boolean evaluateTest(Description description) {
      String methodName = description.getMethodName();

      // The method name could be null, e.g. if the class is marked with @Ignore. In that
      // case there is no matching method to run so filter the test out.
      if (methodName == null) {
        return false;
      }

      // Parameterized tests append "[#]" at the end of the method names.
      // For instance, "getFoo" would become "getFoo[0]".
      // Method filters should be applied against both the parameterized name and root name
      String rootMethodName = stripParameterizedSuffix(methodName);
      if (excludedMethods.contains(methodName) || excludedMethods.contains(rootMethodName)) {
        return false;
      }
      // don't filter out descriptions with method name "initializationError", since
      // Junit will generate such descriptions in error cases, See ErrorReportingRunner
      return includedMethods.isEmpty()
          || includedMethods.contains(methodName)
          || includedMethods.contains(rootMethodName)
          || methodName.equals("initializationError");
    }

    // Strips out the parameterized suffix if it exists
    private String stripParameterizedSuffix(String name) {
      Pattern suffixPattern = Pattern.compile(".+(\\[[0-9]+\\])$");
      if (suffixPattern.matcher(name).matches()) {
        name = name.substring(0, name.lastIndexOf('['));
      }
      return name;
    }

    public void addInclusionMethod(String methodName) {
      includedMethods.add(methodName);
    }

    public void addExclusionMethod(String methodName) {
      excludedMethods.add(methodName);
    }
  }

  /**
   * Creates a TestRequestBuilder
   *
   * @param instr the {@link Instrumentation} to pass to applicable tests
   * @param bundle the {@link Bundle} to pass to applicable tests
   */
  public TestRequestBuilder(Instrumentation instr, Bundle bundle) {
    this(new DeviceBuildImpl(), instr, bundle);
  }

  /** Alternate TestRequestBuilder constructor that accepts a custom DeviceBuild */
  @VisibleForTesting
  TestRequestBuilder(DeviceBuild deviceBuildAccessor, Instrumentation instr, Bundle bundle) {
    deviceBuild = Checks.checkNotNull(deviceBuildAccessor);
    this.instr = Checks.checkNotNull(instr);
    argsBundle = Checks.checkNotNull(bundle);

    maybeAddLegacySuppressFilter();
  }

  // add legacy Suppress filer iff it is on classpath
  private void maybeAddLegacySuppressFilter() {
    try {
      Class<? extends Annotation> legacySuppressClass =
          (Class<? extends Annotation>)
              Class.forName("android.test.suitebuilder.annotation.Suppress");
      filter = filter.intersect(new AnnotationExclusionFilter(legacySuppressClass));
    } catch (ClassNotFoundException e) {
      // ignore
    }
  }

  /**
   * Instruct builder to scan the given paths and add all test classes found. Cannot be used in
   * conjunction with {@link #addTestClass} or {@link #addTestMethod} is used.
   *
   * @param paths the list of paths (.dex and .apk files) to scan
   */
  public TestRequestBuilder addPathsToScan(Iterable<String> paths) {
    for (String path : paths) {
      addPathToScan(path);
    }
    return this;
  }

  /**
   * Instruct builder to scan given path and add all test classes found. Cannot be used in
   * conjunction with {@link #addTestClass} or {@link #addTestMethod} is used.
   *
   * @param path a filepath to scan for test methods (.dex and .apk files)
   */
  public TestRequestBuilder addPathToScan(String path) {
    pathsToScan.add(path);
    return this;
  }

  /**
   * Set the {@link ClassLoader} to be used to load test cases.
   *
   * @param loader {@link ClassLoader} to load test cases with.
   */
  public TestRequestBuilder setClassLoader(ClassLoader loader) {
    classLoader = loader;
    return this;
  }

  /**
   * Instructs the test builder if JUnit3 suite() methods should be executed.
   *
   * @param ignoreSuiteMethods true to ignore all suite methods.
   */
  public TestRequestBuilder ignoreSuiteMethods(boolean ignoreSuiteMethods) {
    this.ignoreSuiteMethods = ignoreSuiteMethods;
    return this;
  }

  /**
   * Add a test class to be executed. All test methods in this class will be executed, unless a test
   * method was explicitly included or excluded.
   *
   * @param className
   */
  public TestRequestBuilder addTestClass(String className) {
    includedClasses.add(className);
    return this;
  }

  /**
   * Excludes a test class. All test methods in this class will be excluded.
   *
   * @param className
   */
  public TestRequestBuilder removeTestClass(String className) {
    excludedClasses.add(className);
    return this;
  }

  /** Adds a test method to run. */
  public TestRequestBuilder addTestMethod(String testClassName, String testMethodName) {
    includedClasses.add(testClassName);
    classMethodFilter.addMethod(testClassName, testMethodName);
    return this;
  }

  /** Excludes a test method from being run. */
  public TestRequestBuilder removeTestMethod(String testClassName, String testMethodName) {
    classMethodFilter.removeMethod(testClassName, testMethodName);
    return this;
  }

  /**
   * Run only tests within given java package. Cannot be used in conjunction with
   * addTestClass/Method.
   *
   * <p>At least one {@link #addPathToScan} also must be provided.
   *
   * @param testPackage the fully qualified java package name
   */
  public TestRequestBuilder addTestPackage(String testPackage) {
    includedPackages.add(testPackage);
    return this;
  }

  /**
   * Excludes all tests within given java package. Cannot be used in conjunction with
   * addTestClass/Method.
   *
   * <p>At least one {@link #addPathToScan} also must be provided.
   *
   * @param testPackage the fully qualified java package name
   */
  public TestRequestBuilder removeTestPackage(String testPackage) {
    excludedPackages.add(testPackage);
    return this;
  }

  /**
   * Sets the test name filter regular expression filter.
   *
   * <p>Will filter out tests not matching the given regex.
   *
   * @param testsRegex a regex for matching against <code>java_package.class#method</code>
   */
  public TestRequestBuilder setTestsRegExFilter(String testsRegex) {
    this.testsRegExFilter.setPattern(testsRegex);
    return this;
  }

  /**
   * Run only tests with given size
   *
   * @param forTestSize
   */
  public TestRequestBuilder addTestSizeFilter(TestSize forTestSize) {
    if (!TestSize.NONE.equals(forTestSize)) {
      addFilter(new SizeFilter(forTestSize));
    } else {
      Log.e(TAG, String.format("Unrecognized test size '%s'", forTestSize.getSizeQualifierName()));
    }
    return this;
  }

  /**
   * Only run tests annotated with given annotation class.
   *
   * @param annotation the full class name of annotation
   */
  public TestRequestBuilder addAnnotationInclusionFilter(String annotation) {
    Class<? extends Annotation> annotationClass = loadAnnotationClass(annotation);
    if (annotationClass != null) {
      addFilter(new AnnotationInclusionFilter(annotationClass));
    }
    return this;
  }

  /**
   * Skip tests annotated with given annotation class.
   *
   * @param notAnnotation the full class name of annotation
   */
  public TestRequestBuilder addAnnotationExclusionFilter(String notAnnotation) {
    Class<? extends Annotation> annotationClass = loadAnnotationClass(notAnnotation);
    if (annotationClass != null) {
      addFilter(new AnnotationExclusionFilter(annotationClass));
    }
    return this;
  }

  public TestRequestBuilder addShardingFilter(int numShards, int shardIndex) {
    return addFilter(new ShardingFilter(numShards, shardIndex));
  }

  public TestRequestBuilder addFilter(Filter filter) {
    this.filter = this.filter.intersect(filter);
    return this;
  }

  public TestRequestBuilder addCustomRunnerBuilderClass(
      Class<? extends RunnerBuilder> runnerBuilderClass) {
    customRunnerBuilderClasses.add(runnerBuilderClass);
    return this;
  }

  /**
   * Build a request that will generate test started and test ended events, but will skip actual
   * test execution.
   */
  public TestRequestBuilder setSkipExecution(boolean b) {
    skipExecution = b;
    return this;
  }

  /** Sets milliseconds timeout value applied to each test where 0 means no timeout */
  public TestRequestBuilder setPerTestTimeout(long millis) {
    perTestTimeout = millis;
    return this;
  }

  /** Convenience method to set builder attributes from {@link RunnerArgs} */
  public TestRequestBuilder addFromRunnerArgs(RunnerArgs runnerArgs) {
    for (RunnerArgs.TestArg test : runnerArgs.tests) {
      if (test.methodName == null) {
        addTestClass(test.testClassName);
      } else {
        addTestMethod(test.testClassName, test.methodName);
      }
    }
    for (RunnerArgs.TestArg test : runnerArgs.notTests) {
      if (test.methodName == null) {
        removeTestClass(test.testClassName);
      } else {
        removeTestMethod(test.testClassName, test.methodName);
      }
    }
    for (String pkg : runnerArgs.testPackages) {
      addTestPackage(pkg);
    }
    for (String pkg : runnerArgs.notTestPackages) {
      removeTestPackage(pkg);
    }
    if (runnerArgs.testSize != null) {
      addTestSizeFilter(TestSize.fromString(runnerArgs.testSize));
    }
    for (String annotation : runnerArgs.annotations) {
      addAnnotationInclusionFilter(annotation);
    }
    for (String notAnnotation : runnerArgs.notAnnotations) {
      addAnnotationExclusionFilter(notAnnotation);
    }
    for (Filter filter : runnerArgs.filters) {
      addFilter(filter);
    }
    if (runnerArgs.testTimeout > 0) {
      setPerTestTimeout(runnerArgs.testTimeout);
    }
    if (runnerArgs.numShards > 0
        && runnerArgs.shardIndex >= 0
        && runnerArgs.shardIndex < runnerArgs.numShards) {
      addShardingFilter(runnerArgs.numShards, runnerArgs.shardIndex);
    }
    if (runnerArgs.logOnly) {
      setSkipExecution(true);
    }
    if (runnerArgs.classLoader != null) {
      setClassLoader(runnerArgs.classLoader);
    }
    for (Class<? extends RunnerBuilder> runnerBuilderClass : runnerArgs.runnerBuilderClasses) {
      addCustomRunnerBuilderClass(runnerBuilderClass);
    }
    if (runnerArgs.testsRegEx != null) {
      setTestsRegExFilter(runnerArgs.testsRegEx);
    }
    return this;
  }

  /**
   * Builds the {@link Request} based on provided data.
   *
   * @throws java.lang.IllegalArgumentException if provided set of data is not valid
   */
  public Request build() {
    includedPackages.removeAll(excludedPackages);
    includedClasses.removeAll(excludedClasses);
    validate(includedClasses);

    boolean scanningPath = includedClasses.isEmpty();

    // If scanning then suite methods are not supported.
    boolean ignoreSuiteMethods = this.ignoreSuiteMethods || scanningPath;

    AndroidRunnerParams runnerParams =
        new AndroidRunnerParams(instr, argsBundle, perTestTimeout, ignoreSuiteMethods);
    RunnerBuilder runnerBuilder = getRunnerBuilder(runnerParams, scanningPath);

    TestLoader loader = TestLoader.testLoader(classLoader, runnerBuilder, scanningPath);
    Collection<String> classNames;
    if (scanningPath) {
      // no class restrictions have been specified. Load all classes.
      classNames = getClassNamesFromClassPath();
    } else {
      classNames = includedClasses;
    }

    List<Runner> runners = loader.getRunnersFor(classNames, scanningPath);

    Suite suite = ExtendedSuite.createSuite(runners);
    Request request = Request.runner(suite);
    return new LenientFilterRequest(request, filter);
  }

  /** Validate that the set of options provided to this builder are valid and not conflicting */
  private void validate(Set<String> classNames) {
    if (classNames.isEmpty() && pathsToScan.isEmpty()) {
      throw new IllegalArgumentException(MISSING_ARGUMENTS_MSG);
    }
    // TODO(b/73905202): consider failing if both test classes and scan paths are given.
    // Right now that is allowed though
  }

  /**
   * Get the {@link RunnerBuilder} to use to create the {@link Runner} instances.
   *
   * @param runnerParams {@link AndroidRunnerParams} that stores common runner parameters
   * @param scanningPath true if being used to build {@link Runner} from classes found while
   *     scanning the path; requires extra checks to avoid unnecessary errors.
   * @return a {@link RunnerBuilder}.
   */
  private RunnerBuilder getRunnerBuilder(AndroidRunnerParams runnerParams, boolean scanningPath) {
    RunnerBuilder builder;
    if (skipExecution) {
      // If all that is needed is the list of tests then replace the Runner which will
      // run the test with one that will simply fire events for each of the tests.
      builder = new AndroidLogOnlyBuilder(runnerParams, scanningPath, customRunnerBuilderClasses);
    } else {
      builder = new AndroidRunnerBuilder(runnerParams, scanningPath, customRunnerBuilderClasses);
    }
    return builder;
  }

  private Collection<String> getClassNamesFromClassPath() {
    if (pathsToScan.isEmpty()) {
      throw new IllegalStateException("neither test class to execute or class paths were provided");
    }
    Log.i(TAG, String.format("Scanning classpath to find tests in paths %s", pathsToScan));
    ClassPathScanner scanner = createClassPathScanner(pathsToScan);

    ChainedClassNameFilter filter = new ChainedClassNameFilter();
    // exclude inner classes
    filter.add(new ExternalClassNameFilter());
    for (String pkg : DEFAULT_EXCLUDED_PACKAGES) {
      // Add the test packages to the exclude list unless they were explictly included.
      if (!includedPackages.contains(pkg)) {
        excludedPackages.add(pkg);
      }
    }
    if (!includedPackages.isEmpty()) {
      filter.add(new InclusivePackageNamesFilter(includedPackages));
    }
    for (String pkg : excludedPackages) {
      filter.add(new ExcludePackageNameFilter(pkg));
    }
    filter.add(new ExcludeClassNamesFilter(excludedClasses));
    try {
      return scanner.getClassPathEntries(filter);
    } catch (IOException e) {
      Log.e(TAG, "Failed to scan classes", e);
    }
    return Collections.emptyList();
  }

  /**
   * Factory method for {@link ClassPathScanner}.
   *
   * <p>Exposed so unit tests can mock.
   */
  ClassPathScanner createClassPathScanner(List<String> classPath) {
    return new ClassPathScanner(classPath);
  }

  @SuppressWarnings("unchecked")
  private Class<? extends Annotation> loadAnnotationClass(String className) {
    try {
      Class<?> clazz = Class.forName(className);
      return (Class<? extends Annotation>) clazz;
    } catch (ClassNotFoundException e) {
      Log.e(TAG, String.format("Could not find annotation class: %s", className));
    } catch (ClassCastException e) {
      Log.e(TAG, String.format("Class %s is not an annotation", className));
    }
    return null;
  }

  private int getDeviceSdkInt() {
    return deviceBuild.getSdkVersionInt();
  }

  private String getDeviceHardware() {
    return deviceBuild.getHardware();
  }

  private String getDeviceCodeName() {
    return deviceBuild.getCodeName();
  }
}
