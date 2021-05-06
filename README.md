# AndroidX Test Library

The AndroidX Test Library provides an extensive framework for testing Android apps. This library provides a set of APIs that allow you to quickly build and run test code for your apps, including JUnit 4 and functional user interface (UI) tests. You can run tests created using these APIs from the Android Studio IDE or from the command line.All packages in AndroidX live in a consistent namespace starting with the string androidx. The Support Library packages have been mapped into corresponding androidx.* packages. For a full mapping of all the old classes and build artifacts to the new ones, see the Package Refactoring page.

For more details see [developers.android.com/testing](https://developers.android.com/testing)

## Contributing

See [CONTRIBUTING.md](https://github.com/android/android-test/blob/master/CONTRIBUTING.md)

## Issues

We use the
[GitHub issue tracker](https://github.com/android/android-test/issues) for
tracking feature requests and bugs.

Please see the
[AndroidX Test Discuss mailing list](https://groups.google.com/forum/#!forum/androidx-test-discuss)
for general questions and discussion, and please direct specific questions to
[Stack Overflow](https://stackoverflow.com/questions/tagged/androidx-test).

## Bazel integration

To depend on this repository in Bazel, add the following snippet to your
WORKSPACE file:

```
ATS_TAG = "<release-tag>"
http_archive(
    name = "android_test_support",
    sha256 = "<sha256 of release>",
    strip_prefix = "android-test-%s" % ATS_TAG,
    urls = ["https://github.com/android/android-test/archive/%s.tar.gz" % ATS_TAG],
)
load("@android_test_support//:repo.bzl", "android_test_repositories")
android_test_repositories()
```
