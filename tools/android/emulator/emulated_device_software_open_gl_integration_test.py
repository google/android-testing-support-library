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

"""Large tests which actually starts an emulator."""
from __future__ import print_function



import os


from absl import flags
import portpicker

from tools.android.emulator import resources
from google.apputils import basetest as googletest
from tools.android.emulator import emulated_device
from tools.android.emulator import fake_android_platform_util

FLAGS = flags.FLAGS
flags.DEFINE_string('api_level', '',
                    'Api level of system images')

root_dir = os.path.abspath(resources.GetRunfilesDir())


class EmulatedDeviceSoftwareOpenGlIntegrationTest(googletest.TestCase):

  def testStartEmulator_x86(self):
    self._Test(0)  # 0 starts no X11VncServer

  def testStartEmulator_x86_with_x11vnc(self):
    self._Test(portpicker.PickUnusedPort())

  def _Test(self, start_vnc_on_port):
    device = None
    attempts = 0
    last_err = None

    while attempts < 1 and not device:
      try:
        attempts += 1
        device = emulated_device.EmulatedDevice(
            android_platform=fake_android_platform_util.BuildAndroidPlatform())
        default_props = {
            'ro.product.model': 'SuperAwesomePhone 3000',
            'ro.mobile_ninjas.emulator_type': 'qemu2',
        }
        if int(FLAGS.api_level) > 19:
          default_props['ro.initial_se_linux_mode'] = 'disabled'

        device.Configure(
            fake_android_platform_util.GetSystemImageDir(),
            '800x480',
            '1024',
            233,
            36,
            kvm_present=True,
            source_properties={'systemimage.abi': 'x86',
                               'androidversion.apilevel': FLAGS.api_level,
                               'systemimage.gpusupport': 'yes'},
            default_properties=default_props)
        device.StartDevice(False, start_vnc_on_port, open_gl_driver='mesa')
        get_prop_output = device.ExecOnDevice(['getprop'])
        device.KillEmulator(politely=True)
        device.CleanUp()
      except emulated_device.TransientEmulatorFailure as e:
        device = None
        last_err = e

    if not device:
      self.fail(last_err)

    # Vals for this flag: -1 not an emulator, 0 emulator which doesn't support
    # open gl, 1 emulator which supports opengl.
    print(get_prop_output)
    self.assertTrue('[ro.kernel.qemu.gles]: [1]' in get_prop_output)

if __name__ == '__main__':
  googletest.main()
