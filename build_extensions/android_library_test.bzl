"""A rule wrapper for an android_instrumentation_test for an android library."""

load("//tools/build_defs/android:rules.bzl", "android_binary", "android_library")
load("//tools/build_defs/kotlin:rules.bzl", "kt_android_library")
load(
    "//third_party/android/androidx_test/build_extensions:android_multidevice_instrumentation_test.bzl",
    "android_multidevice_instrumentation_test",
)
load(
    "//third_party/android/androidx_test/build_extensions:infer_java_package_name.bzl",
    "infer_java_package_name",
    "infer_java_package_name_from_label",
)
load("//devtools/build_cleaner/skylark:build_defs.bzl", "register_extension_info")

# registers the wrapper with build_cleaner so it can manage dependencies automatically
register_extension_info(
    extension_name = "android_library_test",
    label_regex_for_dep = "{extension_name}_library",
)

def android_library_test(
        name,
        target_devices,
        deps = [],
        srcs = [],
        custom_package = None,
        javacopts = None,
        manifest = None,
        manifest_values = {},
        nocompress_extensions = None,
        multidex = None,
        tags = [],
        test_class = None,
        permissions = [],
        **kwargs):
    """A macro for an instrumentation test whose target under test is an android_library.

    Will generate a 'self-instrumentating' test binary and other associated rules

    The intent of this wrapper is to simplify the build API for creating instrumentation test rules
    for simple cases, while still supporting build_cleaner for automatic dependency management.

    This will generate:
      - a test_lib android_library, containing all sources and dependencies
      - a test android_binary (soon to be android_application)
      - the manifest to use for the test library.
      - for each device combination:
         - an android_instrumentation_test rule)

    Args:
        name: the unique name for the target, used to derive names for the generated target
        target_devices: the devices to execute on
        deps: The list of other libraries to link against, passed to android_library
        srcs: The list of .java or .kt sources, passed to android_library
        custom_package: optional, Java package for which java sources will be generated. Passed to
            both android_library and android_binary
        javacopts: optional, Extra compiler options for this target, passed to android_library
        manifest: optional, the AndroidManifest.xml to pass to android_library
        manifest_values: A dictionary of values to be overridden in the manifest., passed to android_binary.
        nocompress_extensions: A list of file extension to leave uncompressed in apk, passed to android_binary
        multidex: optional, whether to split code into multiple dex files, passed to android_binary
        tags: passed to android_library, android_binary and a_i_t
        test_class: The test class to run.  If this argument is omitted, the Java class whose name corresponds
            to the name of this android_library_test rule + current directory path will be used. The test class
            needs to be annotated with org.junit.runner.RunWith.
        permissions: list of permissions the test application should hold. This is necessary since permission
            declarations are not merged from transitive dependencies
        **kwargs: arguments to pass to android_instrumentation_test
    """
    test_application_id = custom_package if custom_package else infer_java_package_name()
    _android_application_test(
        name = name,
        srcs = srcs,
        deps = deps,
        target_devices = target_devices,
        custom_package = custom_package,
        javacopts = javacopts,
        manifest = manifest,
        manifest_values = manifest_values,
        nocompress_extensions = nocompress_extensions,
        multidex = multidex,
        tags = tags,
        test_class = test_class,
        instruments = None,
        # make this self instrumenting
        instruments_application_id = test_application_id,
        test_application_id = test_application_id,
        permissions = permissions,
        **kwargs
    )

def _android_application_test(
        name,
        srcs,
        deps,
        target_devices,
        instruments,
        instruments_application_id = None,
        test_application_id = None,
        custom_package = None,
        javacopts = None,
        manifest = None,
        manifest_values = {},
        nocompress_extensions = None,
        multidex = None,
        tags = [],
        test_class = None,
        permissions = [],
        **kwargs):
    """A macro for an instrumentation test whose target under test is an android_application.

    Will generate a test binary and other associated rules

    The intent of this wrapper is to simplify the build API for creating instrumentation test rules
    for simple cases, while still supporting build_cleaner for automatic dependency management.

    This will generate:
      - a test_lib android_library, containing all sources and dependencies
      - a test_binary android_binary (soon to be android_application)
      - the manifest to use for the test library.
      - for each device combination:
         - an android_instrumentation_test rule)

    TODO(brettchabot): keep this hidden for now - its unclear if there is a use case here,
    and there are some unknowns with using this.

    Args:
        name: the unique name for the target, used to derive names for the generated target
        deps: The list of other libraries to link against, passed to android_library
        srcs: The list of .java or .kt sources, passed to android_library
        instruments: the android_binary to instrument.
        instruments_application_id: the application id for the instruments android_binary. If unspecified, will be derived based on package path of android_binary
        test_application_id: the application id for the test_app android_binary. If unspecified, will be derived based on package path
        custom_package: optional, Java package for which java sources will be generated. Passed to both android_library and android_binary
        javacopts: optional, Extra compiler options for this target, passed to android_library.
        manifest: optional, the AndroidManifest.xml to pass to android_library
        manifest_values: A dictionary of values to be overridden in the manifest., passed to android_binary.
        nocompress_extensions: A list of file extension to leave uncompressed in apk, passed to android_binary
        multidex: optional, whether to split code into multiple dex files, passed to android_binary
        tags: passed to android_library, android_binary and a_i_t
        target_devices: the devices to execute on
        test_class: The test class to run.  If this argument is omitted, the Java class whose name corresponds to the name of this android_library_test rule + current directory path will be used. The test class needs to be annotated with org.junit.runner.RunWith.
        permissions: list of permissions the test application should hold. This is necessary since
            permission declarations are not merged from transitive dependencies
        **kwargs: arguments to pass to android_instrumentation_test
    """
    library_name = "%s_library" % name
    test_application_id = test_application_id if test_application_id else infer_java_package_name()
    instruments_application_id = instruments_application_id if instruments_application_id else infer_java_package_name_from_label(instruments)

    is_kotlin = any([".kt" in src for src in srcs])
    if is_kotlin:
        kt_android_library(
            name = library_name,
            srcs = srcs,
            testonly = 1,
            manifest = manifest,
            deps = deps,
            custom_package = custom_package,
            javacopts = javacopts,
            tags = tags,
        )
    else:
        android_library(
            name = library_name,
            srcs = srcs,
            testonly = 1,
            manifest = manifest,
            deps = deps,
            custom_package = custom_package,
            javacopts = javacopts,
            tags = tags,
        )

    _permission_string = ""
    for _permission in permissions:
        _permission_string = "<uses-permission android:name=\\\"%s\\\"/>\\n" % _permission

    # for some unknown reason _manifest_values doesn't replace permissionList properly
    # so directly use sed
    native.genrule(
        name = "%s_manifest_genrule" % name,
        srcs = ["//third_party/android/androidx_test/build_extensions:AndroidManifest_instrumentation_test_template.xml"],
        outs = ["%s_manifest_with_permissions.xml" % name],
        cmd = "sed s,permissionList,\"%s\",g $(SRCS) > $(OUTS)" % _permission_string,
    )

    _manifest_values = {
        "applicationId": test_application_id,
        "instrumentationTargetPackage": instruments_application_id,
    }
    _manifest_values.update(manifest_values)
    android_binary(
        name = "%s_binary" % name,
        manifest = "%s_manifest_with_permissions.xml" % name,
        testonly = 1,
        instruments = instruments,
        manifest_values = _manifest_values,
        deps = [
            ":%s" % library_name,
            "//third_party/android/androidx_test/runner/android_junit_runner",
        ],
        nocompress_extensions = nocompress_extensions,
        custom_package = custom_package,
        multidex = multidex,
        tags = tags,
    )

    test_class = test_class if test_class else test_application_id + "." + name
    android_multidevice_instrumentation_test(
        name = name,
        target_devices = target_devices,
        tags = tags,
        test_app = ":%s_binary" % name,
        test_class = test_class,
        **kwargs
    )
