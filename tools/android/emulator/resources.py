# Copyright 2017 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Local implementation of resources.

"""
import os
import sys


_GOOGLE_STR = os.sep + 'android_test_support' + os.sep


def GetRunfilesDir():
  starting_point = sys.argv[0]
  return FindRunfilesDir(os.path.abspath(starting_point))


def GetResourceAsFile(file_path, mode='r'):
  return open(GetResourceFilename(file_path), mode=mode)

def GetResourceFilename(file_path):
  if os.path.isabs(file_path):
    return file_path
  else:
    return os.path.join(GetRunfilesDir(), file_path)


def FindRunfilesDir(program_filename):
  """Look for a runfiles directory corresponding to the given program.

  Args:
    program_filename: absolute path to a Python program
  Returns:
    The path to the runfiles directory, or None if one wasn't found.
  """
  def _GetBinaryDirectoryFilename(filename):
    """Find a match for the binary filename and its path.

    If the binary directory isn't known, search the program's
    filename for a binary directory.

    Args:
      filename: The name of the binary file.

    Returns:
      A tuple of the binary directory, and the filename relative to that
      directory.
      If the binary directory isn't known, search the program's
      filename for a binary directory
    """
    # first, see if filename begins with a bin directory
    for bindir in ['bin', 'bazel-bin']:
      bindir_sep = bindir + os.sep
      if filename.startswith(bindir_sep):
        filename = filename[len(bindir_sep):]
        return bindir, filename

    # if not, find the bin directory in the absolute programname
    for elem in os.path.abspath(sys.argv[0]).split(os.sep):
      if elem in ['bin', 'bazel-bin']:
        return elem, filename
    # shouldn't happen but will fail os.path.isdir below
    return '', filename

  google_idx = program_filename.rfind(_GOOGLE_STR)
  if google_idx != -1:
    root_dir = program_filename[:google_idx]
    rel_filename = program_filename[google_idx + len(_GOOGLE_STR):]
    bindir, rel_filename = _GetBinaryDirectoryFilename(rel_filename)
    rel_filename_noext = os.path.splitext(rel_filename)[0]
    runfiles = os.path.join(root_dir, 'android_test_support', bindir,
                            rel_filename_noext + '.runfiles')
    if os.path.isdir(runfiles):
      return runfiles
    return root_dir
  else:
    return None
