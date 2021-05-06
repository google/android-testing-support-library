# TODO(b/114418172): rename to androidx_test. Requires a bazel change
workspace(name = "android_test_support")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "2.1"

RULES_JVM_EXTERNAL_SHA = "515ee5265387b88e4547b34a57393d2bcb1101314bcc5360ec7a482792556f42"

http_archive(
    name = "rules_jvm_external",
    sha256 = RULES_JVM_EXTERNAL_SHA,
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

# rules_proto defines proto_library.
http_archive(
    name = "rules_proto",
    sha256 = "2490dca4f249b8a9a3ab07bd1ba6eca085aaf8e45a734af92aad0c42d9dc7aaf",
    strip_prefix = "rules_proto-218ffa7dfa5408492dc86c01ee637614f8695c45",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/rules_proto/archive/218ffa7dfa5408492dc86c01ee637614f8695c45.tar.gz",
        "https://github.com/bazelbuild/rules_proto/archive/218ffa7dfa5408492dc86c01ee637614f8695c45.tar.gz",
    ],
)

load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
rules_proto_dependencies()
rules_proto_toolchains()

# The 'com_google_protobuf_javalite' package is required for Bazel 2.x and below.
http_archive(
    name = "com_google_protobuf_javalite",
    sha256 = "832c476bb442ca98a59c2291b8a504648d1c139b74acc15ef667a0e8f5e984e7",
    strip_prefix = "protobuf-3.11.3",
    urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.11.3.zip"],
)

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")
load(
    "//build_extensions:axt_versions.bzl",
    "ANDROIDX_JUNIT_VERSION",
    "ANDROIDX_LIFECYCLE_VERSION",
    "ANDROIDX_MULTIDEX_VERSION",
    "ANDROIDX_VERSION",
    "CORE_VERSION",
    "GOOGLE_MATERIAL_VERSION",
    "RUNNER_VERSION",
    "UIAUTOMATOR_VERSION",
)

maven_install(
    name = "maven",
    artifacts = [
        "androidx.annotation:annotation:" + ANDROIDX_VERSION,
        "androidx.annotation:annotation-experimental:jar:" + ANDROIDX_VERSION,
        "androidx.appcompat:appcompat:" + ANDROIDX_VERSION,
        "androidx.core:core:" + ANDROIDX_VERSION,
        "androidx.cursoradapter:cursoradapter:" + ANDROIDX_VERSION,
        "androidx.drawerlayout:drawerlayout:" + ANDROIDX_VERSION,
        "androidx.fragment:fragment:" + ANDROIDX_VERSION,
        "androidx.legacy:legacy-support-core-ui:" + ANDROIDX_VERSION,
        "androidx.legacy:legacy-support-core-utils:" + ANDROIDX_VERSION,
        "androidx.legacy:legacy-support-v4:" + ANDROIDX_VERSION,
        "androidx.lifecycle:lifecycle-common:" + ANDROIDX_LIFECYCLE_VERSION,
        "androidx.multidex:multidex:" + ANDROIDX_MULTIDEX_VERSION,
        "androidx.recyclerview:recyclerview:" + ANDROIDX_VERSION,
        "androidx.test.uiautomator:uiautomator:" + UIAUTOMATOR_VERSION,
        "androidx.viewpager:viewpager:" + ANDROIDX_VERSION,
        "aopalliance:aopalliance:1.0",
        "com.beust:jcommander:1.72",
                maven.artifact(
            group = "com.google.android.apps.common.testing.accessibility.framework",
            artifact = "accessibility-test-framework",
            version = "3.1",
            exclusions = [
             # exclude the org.checkerframework dependency since that require
             # java8 compatibility. See b/176926990
                maven.exclusion(
                    group = "org.checkerframework",
                    artifact = "checker"
                    ),
                ]
            ),

        "com.google.android.material:material:" + GOOGLE_MATERIAL_VERSION,
        "com.google.auto.value:auto-value:1.5.1",
        "com.google.code.findbugs:jsr305:3.0.2",
        "com.google.code.gson:gson:2.8.5",
        "com.google.dagger:dagger-compiler:2.11",
        "com.google.dagger:dagger-producers:2.11",
        "com.google.dagger:dagger:2.10",
        "com.google.errorprone:javac-shaded:9-dev-r4023-3",
        "com.google.errorprone:error_prone_annotation:2.3.4",
        "com.google.flogger:flogger-system-backend:0.4",
        "com.google.flogger:flogger:0.4",
        "com.google.flogger:google-extensions:0.4",
        "com.google.googlejavaformat:google-java-format:1.4",
        "com.google.guava:guava:27.1-android",
        "com.google.guava:guava-testlib:27.1-android",
        "com.google.inject.extensions:guice-multibindings:4.1.0",
        "com.google.inject:guice:4.1.0",
        "com.google.truth:truth:1.0",
        "com.googlecode.jarjar:jarjar:1.3",
        "com.linkedin.dexmaker:dexmaker-mockito:jar:2.25.0",
        "com.linkedin.dexmaker:dexmaker:2.25.0",
        "com.squareup:javapoet:1.9.0",
        "javax.annotation:javax.annotation-api:1.3.1",
        "javax.inject:javax.inject:1",
        "joda-time:joda-time:2.10.1",
        "junit:junit:4.12",
        "net.bytebuddy:byte-buddy-agent:1.9.11",
        "net.bytebuddy:byte-buddy:1.9.11",
        "net.sf.kxml:kxml2:jar:2.3.0",
        "org.ccil/cowan.tagsoup:tagsoup:1.2",
        "org.checkerframework:checker-compat-qual:2.5.5",
        "org.hamcrest:hamcrest-all:1.3",
        "org.mockito:mockito-core:2.25.0",
        "org.objenesis:objenesis:2.1",
        "org.pantsbuild:jarjar:1.7.2",
        "org.robolectric:robolectric:4.4",
    ],
    repositories = [
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
        "https://dl.bintray.com/linkedin/maven",
    ],
)

android_sdk_repository(
    name = "androidsdk",
    api_level = 30,
    build_tools_version = "30.0.2",
)

load("//:repo.bzl", "android_test_repositories")
android_test_repositories(with_dev_repositories = True)

load("@robolectric//bazel:robolectric.bzl", "robolectric_repositories")
robolectric_repositories()

# Kotlin toolchains
rules_kotlin_version = "686518ffd8e58609a21f258616d154ba2934a8e8"
http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "0237910a921ad492aa8520bf88923c42d745d74522d3507a34c9bfd39b4e295c",
    strip_prefix = "rules_kotlin-%s" % rules_kotlin_version,
    type = "zip",
    urls = ["https://github.com/bazelbuild/rules_kotlin/archive/%s.zip" % rules_kotlin_version],
)
load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kotlin_repositories", "kt_register_toolchains")
kotlin_repositories()
kt_register_toolchains()

# Android bazel rules
http_archive(
    name = "build_bazel_rules_android",
    urls = ["https://github.com/bazelbuild/rules_android/archive/v0.1.1.zip"],
    sha256 = "cd06d15dd8bb59926e4d65f9003bfc20f9da4b2519985c27e190cddc8b7a7806",
    strip_prefix = "rules_android-0.1.1",
)
