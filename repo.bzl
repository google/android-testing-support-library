"""Skylark rules to setup the WORKSPACE in the opensource bazel world."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# These dependencies are required for *developing* this project.
def _development_repositories():
    # Needed by @com_google_protobuf//:protobuf_java_util
    native.bind(
        name = "guava",
        actual = "@maven//:com_google_guava_guava",
    )

    # Needed by @com_google_protobuf//:protobuf_java_util
    native.bind(
        name = "gson",
        actual = "@maven//:com_google_code_gson_gson",
    )

    http_archive(
        name = "robolectric",
        sha256 = "d4f2eb078a51f4e534ebf5e18b6cd4646d05eae9b362ac40b93831bdf46112c7",
        strip_prefix = "robolectric-bazel-4.4",
        urls = ["https://github.com/robolectric/robolectric-bazel/archive/4.4.tar.gz"],
    )
    # uncomment to test with new robolectric version. Change path to point to local filesystem
    # clone of https://github.com/robolectric/robolectric-bazel
    # native.local_repository(
    #     name = "robolectric",
    #     path = "~/github/robolectric-bazel/",
    # )

    http_archive(
        name = "jsr330",
        build_file_content = """
package(default_visibility = ["//visibility:public"])
java_import(
    name = "jsr330",
    jars = ["javax.inject.jar"],
)""",
        url = "https://github.com/javax-inject/javax-inject/releases/download/1/javax.inject.zip",
    )

# These dependencies are for *users* of the Android Test repo,
# i.e. specifying this repository as @androidx_test in their
# WORKSPACE using a repository_rule like git_repository or http_archive.
# Use parameter `with_dev_repositories = True` to download the dev
# repositories as well.
def android_test_repositories(with_dev_repositories = False, emulator_launcher_target = "//tools/android/emulator:unified_launcher_head_default"):
    """Loads the workspace by downloading the required dependencies."""

    native.bind(
        name = "emulator_launcher_target",
        actual = emulator_launcher_target,
    )

    if with_dev_repositories:
        _development_repositories()

    # Several of the repositories here end in _archive. This is due to an issue
    # with the Bazel python rules that occurs if the repository name is the same
    # as the top level directory of that repository. For example, suppose //:a
    # depends on @b//:b and @b//:b is defined by
    #
    #     filegroup(
    #         name = "b",
    #         srcs = ["b/c.py"],
    #     )
    #
    # Then the execroot looks like
    #
    #     bazel-bin/
    #       - a
    #       - a.runfiles/
    #           - b/
    #               - b/
    #                   - c.py
    #                   - __init__.py
    #               - __init__.py
    #           - __main__/
    #               - a
    #               - a.py
    #           - __init__.py
    #
    # The Python path for a.py contains, in order, the following:
    #
    #     [a.runfiles, a.runfiles/b, a.runfiles/__main__]
    #
    # If a.py contains `from b import c`, then python will find a.runfiles on
    # the Python path, see that it contains both __init__.py and b/ but does not
    # contain c.py and will error out.
    #
    # On the other hand, if we call @b//:b @b_archive//:b, then the first entry on
    # the python path containing b/ is a.runfiles/b_archive which contains all of
    # __init__.py, b/ and b/c.py so the import will succeed.

    http_archive(
        name = "google_apputils",
        build_file = str(Label("//opensource:google-apputils.BUILD")),
        sha256 = "47959d0651c32102c10ad919b8a0ffe0ae85f44b8457ddcf2bdc0358fb03dc29",
        strip_prefix = "google-apputils-0.4.2",
        url = "https://pypi.python.org/packages/69/66/a511c428fef8591c5adfa432a257a333e0d14184b6c5d03f1450827f7fe7/google-apputils-0.4.2.tar.gz",
    )

    http_archive(
        name = "gflags_archive",
        build_file = str(Label("//opensource:gflags.BUILD")),
        sha256 = "3377d9dbeedb99c0325beb1f535f8fa9fa131d1d8b50db7481006f0a4c6919b4",
        strip_prefix = "python-gflags-3.1.0",
        url = "https://github.com/google/python-gflags/releases/download/3.1.0/python-gflags-3.1.0.tar.gz",
    )

    http_archive(
        name = "portpicker_archive",
        build_file = str(Label("//opensource:portpicker.BUILD")),
        sha256 = "2f88edf7c6406034d7577846f224aff6e53c5f4250e3294b1904d8db250f27ec",
        strip_prefix = "portpicker-1.1.1/src",
        url = "https://pypi.python.org/packages/96/48/0e1f20fdc0b85cc8722284da3c5b80222ae4036ad73210a97d5362beaa6d/portpicker-1.1.1.tar.gz",
    )

    http_archive(
        name = "mox_archive",
        build_file = str(Label("//opensource:mox.BUILD")),
        sha256 = "424ee725ee12652802b4e86571f816059b0d392401ceae70bf6487d65602cba9",
        strip_prefix = "mox-0.5.3",
        url = "https://pypi.python.org/packages/0c/a1/64740c638cc5fae807022368f4141700518ee343b53eb3e90bf3cc15a4d4/mox-0.5.3.tar.gz#md5=6de7371e7e8bd9e2dad3fef2646f4a43",
    )

    # Six provides simple utilities for wrapping over differences between Python 2 and Python 3.
    http_archive(
        name = "six_archive",
        build_file = str(Label("//opensource:six.BUILD")),
        sha256 = "105f8d68616f8248e24bf0e9372ef04d3cc10104f1980f54d57b2ce73a5ad56a",
        strip_prefix = "six-1.10.0",
        url = "https://pypi.python.org/packages/source/s/six/six-1.10.0.tar.gz",
    )

    # Needed by protobuf
    native.bind(name = "six", actual = "@six_archive//:six")

    # Protobuf
    http_archive(
        name = "com_google_protobuf",
        sha256 = "d82eb0141ad18e98de47ed7ed415daabead6d5d1bef1b8cccb6aa4d108a9008f",
        strip_prefix = "protobuf-b4f193788c9f0f05d7e0879ea96cd738630e5d51",
        # Commit from 2019-05-15, update to protobuf 3.8 when available.
        url = "https://github.com/protocolbuffers/protobuf/archive/b4f193788c9f0f05d7e0879ea96cd738630e5d51.tar.gz",
    )

    # Protobuf's dependencies

    # Inlined protobuf's deps so we don't need users to add protobuf_deps() to their local WORKSPACE.
    # From load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps").
    http_archive(
        name = "zlib",
        build_file = "@com_google_protobuf//:third_party/zlib.BUILD",
        sha256 = "c3e5e9fdd5004dcb542feda5ee4f0ff0744628baf8ed2dd5d66f8ca1197cb1a1",
        strip_prefix = "zlib-1.2.11",
        urls = ["https://zlib.net/zlib-1.2.11.tar.gz"],
    )

    http_archive(
        name = "bazel_skylib",
        url = "https://github.com/bazelbuild/bazel-skylib/releases/download/0.8.0/bazel-skylib.0.8.0.tar.gz",
        sha256 = "2ef429f5d7ce7111263289644d233707dba35e39696377ebab8b0bc701f7818e",
    )

    # Open source version of the google python flags library.
    http_archive(
        name = "absl_py",
        sha256 = "980ce58c34dfa75a9d20d45c355658191c166557f1de41ab52f208bd00604c2b",
        strip_prefix = "abseil-py-b347ba6022370f895d3133241ed96965b95ecb40",
        urls = ["https://github.com/abseil/abseil-py/archive/b347ba6022370f895d3133241ed96965b95ecb40.tar.gz"],
    )
