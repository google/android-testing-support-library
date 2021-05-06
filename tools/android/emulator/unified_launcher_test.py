# Copyright 2012 Google Inc. All Rights Reserved.
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

"""Tests for android_test_support.third_party.android.android_test_support.tools.android.emulator.unified_launcher."""



import collections
import os
import tempfile


from absl import flags
import mox

from io import StringIO

from google.apputils import basetest as googletest
from tools.android.emulator import emulated_device
from tools.android.emulator import emulator_meta_data_pb2
from tools.android.emulator import reporting
from tools.android.emulator import unified_launcher

FLAGS = flags.FLAGS


class UnifiedLauncherTest(mox.MoxTestBase):

  def setUp(self):
    super(UnifiedLauncherTest, self).setUp()
    base_temp = tempfile.mkdtemp()
    self._tempdir = os.path.join(base_temp, 'android_test_support')
    os.mkdir(self._tempdir)
    FLAGS.flag_configured_android_tools = False
    FLAGS.enable_gms_usage_reporting = True
    self._test_proto = emulator_meta_data_pb2.EmulatorMetaDataPb()
    self._test_proto.skin = '800x900'
    self._test_proto.memory_mb = 1024
    self._test_proto.density = 133
    self._test_proto.vm_heap = 36
    self._test_proto.net_type = 'fastnet'
    self._test_proto.sdcard_size_mb = 256

  def _WriteTestProto(self):
    test_file = open(os.path.join(self._tempdir,
                                  unified_launcher._METADATA_FILE_NAME), 'wb')
    test_file.write(self._test_proto.SerializeToString())
    test_file.close()

  def testRestartDevice_noProto(self):
    self.mox.ReplayAll()
    self.assertRaises(AssertionError, unified_launcher._RestartDevice, None,
                      False, 0, 'fastnet', ['system.img', 'userdata.img'],
                      'foo.dat', '/i/donot/exist')

  def testStopDeviceAndOutputState(self):
    mock_device = emulated_device.EmulatedDevice()
    self.mox.StubOutWithMock(mock_device, 'TakeSnapshot')
    self.mox.StubOutWithMock(mock_device, 'KillEmulator')
    self.mox.StubOutWithMock(mock_device, 'StoreAndCompressUserdata')
    self.mox.StubOutWithMock(mock_device, 'GetEmulatorMetadataProto')
    mock_device.KillEmulator(politely=True)

    output_dir = tempfile.mkdtemp()

    mock_device.StoreAndCompressUserdata(os.path.join(output_dir,
                                                      'userdata_images.dat'),
                                         None)
    mock_device.GetEmulatorMetadataProto().AndReturn(self._test_proto)

    self.mox.ReplayAll()

    unified_launcher._StopDeviceAndOutputState(mock_device,
                                               output_dir)
    self.assertTrue(os.path.exists(os.path.join(
        output_dir, unified_launcher._METADATA_FILE_NAME)))

    written_file = open(os.path.join(output_dir,
                                     unified_launcher._METADATA_FILE_NAME),
                        'rb')
    written_proto = emulator_meta_data_pb2.EmulatorMetaDataPb()
    written_proto.ParseFromString(written_file.read())
    written_file.close()
    self.assertEquals(written_proto, self._test_proto)

  def testRun_badInstall(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3
    vnc_port = 4
    net_type = 'fastnet'
    reporter = reporting.NoOpReporter()

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        enable_single_step=False,
        qemu_gdb_port=0,
        logcat_path='/foo/bar',
        logcat_filter='FILTER',
        enable_console_auth=False,
        enable_g3_monitor=True,
        enable_gps=True,
        add_insecure_cert=False,
        reporter=reporter,
        mini_boot=False,
        sim_access_rules_file=None,
        phone_number=None,
        source_properties=None,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)

    self.mox.StubOutWithMock(unified_launcher, '_RestartDevice')
    unified_launcher._RestartDevice(mock_device,
                                    enable_display=True,
                                    start_vnc_on_port=vnc_port,
                                    net_type=net_type,
                                    input_image_file=None,
                                    proto_filepath=None,
                                    system_image_files=None,
                                    open_gl_driver=emulated_device.NO_OPEN_GL,
                                    new_process_group=False,
                                    with_audio=False,
                                    window_scale=None,
                                    with_boot_anim=False,
                                    emulator_tmp_dir=None,
                                    experimental_open_gl=False,
                                    snapshot_file=None)
    self.mox.StubOutWithMock(mock_device, 'InstallApk')
    self.mox.StubOutWithMock(mock_device, 'KillEmulator')
    self.mox.StubOutWithMock(mock_device, 'CleanUp')

    mock_device.InstallApk('bad_apk').AndRaise(Exception('failure!'))
    mock_device.KillEmulator()
    mock_device.SyncTime()

    self.mox.ReplayAll()

    self.assertRaises(Exception, unified_launcher._Run,
                      adb_server_port,
                      emulator_port,
                      adb_port,
                      True,
                      vnc_port,
                      '/foo/bar',
                      'FILTER',
                      system_images=None,
                      input_image_file=None,
                      emulator_metadata_path=None,
                      apks=['bad_apk'],
                      system_apks=[],
                      net_type='fastnet',
                      open_gl_driver=emulated_device.NO_OPEN_GL,
                      new_process_group=False,
                      reporter=reporter,
                      mini_boot=False)

  def testRun_noInstall(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3
    vnc_port = 4
    reporter = reporting.NoOpReporter()

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        enable_single_step=False,
        qemu_gdb_port=0,
        logcat_path='/foo/bar',
        logcat_filter='FILTER',
        enable_console_auth=False,
        enable_g3_monitor=True,
        enable_gps=True,
        add_insecure_cert=True,
        reporter=reporter,
        mini_boot=False,
        sim_access_rules_file=None,
        phone_number=None,
        source_properties=None,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)

    self.mox.StubOutWithMock(unified_launcher, '_RestartDevice')
    unified_launcher._RestartDevice(mock_device,
                                    enable_display=True,
                                    start_vnc_on_port=vnc_port,
                                    net_type='fastnet',
                                    input_image_file=None,
                                    proto_filepath=None,
                                    system_image_files=None,
                                    open_gl_driver=emulated_device.NO_OPEN_GL,
                                    new_process_group=True,
                                    with_audio=False,
                                    window_scale=None,
                                    with_boot_anim=False,
                                    emulator_tmp_dir=None,
                                    experimental_open_gl=False,
                                    snapshot_file=None)
    self.mox.StubOutWithMock(mock_device, 'InstallApk')
    self.mox.StubOutWithMock(mock_device, 'InstallCyberVillainsCert')
    mock_device.SyncTime()
    mock_device.InstallCyberVillainsCert()
    mock_device.BroadcastDeviceReady({'test_message': '1234'})
    mock_device.ConnectDevice()

    self.mox.ReplayAll()

    unified_launcher._Run(adb_server_port, emulator_port, adb_port, True,
                          vnc_port,
                          '/foo/bar', 'FILTER',
                          system_images=None,
                          input_image_file=None,
                          emulator_metadata_path=None,
                          apks=[],
                          system_apks=[],
                          net_type='fastnet',
                          new_process_group=True,
                          open_gl_driver=emulated_device.NO_OPEN_GL,
                          broadcast_message={'test_message': '1234'},
                          add_insecure_cert=True,
                          reporter=reporter,
                          mini_boot=False)

  def testRun_goodInstall(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3
    vnc_port = 4
    reporter = reporting.NoOpReporter()

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        enable_single_step=False,
        qemu_gdb_port=0,
        logcat_path='/foo/bar',
        logcat_filter='FILTER',
        enable_console_auth=False,
        enable_g3_monitor=True,
        enable_gps=True,
        add_insecure_cert=False,
        reporter=reporter,
        mini_boot=False,
        sim_access_rules_file=None,
        phone_number=None,
        source_properties=None,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)

    self.mox.StubOutWithMock(unified_launcher, '_RestartDevice')
    unified_launcher._RestartDevice(mock_device, enable_display=True,
                                    start_vnc_on_port=vnc_port,
                                    net_type='fastnet',
                                    input_image_file=None,
                                    proto_filepath=None,
                                    system_image_files=None,
                                    open_gl_driver=emulated_device.NO_OPEN_GL,
                                    new_process_group=False,
                                    with_audio=False,
                                    window_scale=None,
                                    with_boot_anim=False,
                                    emulator_tmp_dir=None,
                                    experimental_open_gl=False,
                                    snapshot_file=None)

    self.mox.StubOutWithMock(mock_device, 'InstallApk')
    mock_device.InstallApk('hello_world', grant_runtime_permissions=True)
    mock_device.InstallApk('goodbye', grant_runtime_permissions=True)
    mock_device.SyncTime()
    mock_device.BroadcastDeviceReady(None)
    mock_device.ConnectDevice()

    self.mox.ReplayAll()

    unified_launcher._Run(adb_server_port, emulator_port, adb_port, True,
                          vnc_port,
                          '/foo/bar', 'FILTER',
                          input_image_file=None,
                          emulator_metadata_path=None,
                          system_images=None,
                          apks=['hello_world', 'goodbye'],
                          open_gl_driver=emulated_device.NO_OPEN_GL,
                          system_apks=[],
                          net_type='fastnet',
                          reporter=reporter,
                          mini_boot=False)

  def testRun_addAccounts(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3
    vnc_port = 4
    accounts = ['user1:password', 'user2:password']
    extras1 = collections.OrderedDict()
    extras1['account_name'] = 'user1'
    extras1['password'] = 'password'
    extras1.update(unified_launcher._ADD_ACCOUNT_BOOLEAN_EXTRAS)
    extras2 = collections.OrderedDict()
    extras2['account_name'] = 'user2'
    extras2['password'] = 'password'
    extras2.update(unified_launcher._ADD_ACCOUNT_BOOLEAN_EXTRAS)
    reporter = reporting.NoOpReporter()

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        enable_single_step=False,
        qemu_gdb_port=0,
        logcat_path='/foo/bar',
        logcat_filter='FILTER',
        enable_console_auth=False,
        enable_g3_monitor=True,
        enable_gps=True,
        add_insecure_cert=False,
        reporter=reporter,
        mini_boot=False,
        sim_access_rules_file=None,
        phone_number=None,
        source_properties=None,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)

    self.mox.StubOutWithMock(unified_launcher, '_RestartDevice')
    unified_launcher._RestartDevice(mock_device, enable_display=True,
                                    start_vnc_on_port=vnc_port,
                                    net_type='fastnet',
                                    input_image_file=None,
                                    proto_filepath=None,
                                    system_image_files=None,
                                    open_gl_driver=emulated_device.NO_OPEN_GL,
                                    new_process_group=False,
                                    with_audio=False,
                                    window_scale=None,
                                    with_boot_anim=False,
                                    emulator_tmp_dir=None,
                                    experimental_open_gl=False,
                                    snapshot_file=None)

    mock_device.SyncTime()
    mock_device.BroadcastDeviceReady(None)
    mock_device.ConnectDevice()
    mock_device.BroadcastDeviceReady(
        extras1, unified_launcher._ADD_ACCOUNT_BROADCAST_ACTION)
    mock_device.BroadcastDeviceReady(
        extras2, unified_launcher._ADD_ACCOUNT_BROADCAST_ACTION)

    self.mox.ReplayAll()

    unified_launcher._Run(adb_server_port, emulator_port, adb_port, True,
                          vnc_port,
                          '/foo/bar', 'FILTER',
                          input_image_file=None,
                          emulator_metadata_path=None,
                          system_images=None,
                          apks=[],
                          open_gl_driver=emulated_device.NO_OPEN_GL,
                          system_apks=[],
                          net_type='fastnet',
                          accounts=accounts,
                          reporter=reporter,
                          mini_boot=False)

  def testPing_live(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        device_serial='localhost:%s' % adb_port)
    self.mox.StubOutWithMock(mock_device, 'Ping')
    mock_device.Ping().AndReturn(True)

    self.mox.ReplayAll()

    unified_launcher._Ping(adb_server_port, emulator_port, adb_port)

  def testPing_dead(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port,
        device_serial='localhost:%s' % adb_port)
    self.mox.StubOutWithMock(mock_device, 'Ping')
    mock_device.Ping().AndReturn(False)

    self.mox.ReplayAll()

    self.assertRaises(AssertionError, unified_launcher._Ping, adb_server_port,
                      emulator_port, adb_port)

  def testKill(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    adb_server_port = 1
    adb_port = 2
    emulator_port = 3

    mock_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        adb_server_port=adb_server_port,
        emulator_telnet_port=emulator_port,
        emulator_adb_port=adb_port)
    self.mox.StubOutWithMock(mock_device, 'KillEmulator')
    mock_device.KillEmulator()

    self.mox.ReplayAll()

    unified_launcher._Kill(adb_server_port, emulator_port, adb_port)

  def testBootApkFilter(self):
    base_dir = tempfile.mkdtemp()
    foo_apk = _CreateFile(os.path.join(base_dir, 'foo.apk'),
                          payload='foov1')
    same_foo = _CreateFile(os.path.join(base_dir, 'same_foo.apk'),
                           payload='foov1')
    test_apk = _CreateFile(os.path.join(base_dir, 'test_apk.apk'),
                           payload='the test')
    app_apk = _CreateFile(os.path.join(base_dir, 'app_apk.apk'),
                          payload='the app')
    self.assertEquals(
        [app_apk, test_apk],
        unified_launcher._RemoveBootTimeApks(
            [foo_apk],
            [same_foo,
             app_apk,
             test_apk]))
    self.assertEquals(
        [app_apk, test_apk],
        unified_launcher._RemoveBootTimeApks(
            [],
            [app_apk, test_apk]))
    self.assertEquals(
        [],
        unified_launcher._RemoveBootTimeApks([], []))
    self.assertEquals(
        [],
        unified_launcher._RemoveBootTimeApks([foo_apk], None))

  def testOverloadedFlagExtraction(self):
    overloaded = [
        '/android_test_support/third_party/java/android_apps/gcore/GmsCore.apk',
        '/android_test_support/com/google/android/apps/common/testing/testapp/testapp.apk',
        '/android/system-images/google_21/x86/kernel-qemu',
        '/android/system-images/google_21/x86/pregen/kernel-qemu',
        '/android/system-images/google_21/x86/pregen/cache.img.tar.gz',
        '/android/system-images/google_21/x86/userdata.img.tar.gz',
        '/android/system-images/google_21/x86/system.img.tar.gz',
        '/android/system-images/google_21/x86/cache.img.tar.gz',
        '/android/system-images/google_21/x86/ramdisk.img']
    self.assertEquals(
        set(['/android_test_support/third_party/java/android_apps/gcore/GmsCore.apk',
             '/android_test_support/com/google/android/apps/common/testing/testapp/'
             'testapp.apk']),
        set(unified_launcher._ExtractBootTimeApks(overloaded)))
    self.assertEquals(
        set(['/android/system-images/google_21/x86/kernel-qemu',
             '/android/system-images/google_21/x86/userdata.img.tar.gz',
             '/android/system-images/google_21/x86/system.img.tar.gz',
             '/android/system-images/google_21/x86/cache.img.tar.gz',
             '/android/system-images/google_21/x86/ramdisk.img']),
        set(unified_launcher._ExtractSystemImages(overloaded)))

  def testBoot_display(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    initial_boot_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        qemu_gdb_port=0,
        enable_single_step=False,
        source_properties=None,
        mini_boot=False,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)
    self.mox.StubOutWithMock(initial_boot_device, 'Configure')
    self.mox.StubOutWithMock(initial_boot_device, 'StartDevice')
    skin = 'rabbit_fur'
    memory = '42'
    initial_boot_device.Configure(
        self._tempdir,
        skin,
        memory,
        133,
        36,
        source_properties=None,
        default_properties=None,
        kvm_present=mox.IsA(bool),
        system_image_path=os.path.join(self._tempdir, 'system.img'),
        data_image_path=os.path.join(self._tempdir, 'userdata.img'),
        vendor_img_path=None,
        encryptionkey_img_path=None,
        advanced_features_ini=None,
        build_prop_path=os.path.join(self._tempdir, 'build.prop'),
        data_files=[])

    initial_boot_device.StartDevice(enable_display=False,
                                    start_vnc_on_port=0,
                                    emulator_tmp_dir=None,
                                    save_snapshot=False,
                                    modified_ramdisk_path=None)

    self.mox.StubOutWithMock(initial_boot_device, 'InstallApk')
    self.mox.StubOutWithMock(initial_boot_device, 'KillEmulator')
    self.mox.StubOutWithMock(initial_boot_device, 'IsInstalled')
    self.mox.StubOutWithMock(unified_launcher, '_StopDeviceAndOutputState')

    initial_boot_device.LogToDevice('Device booted.')
    initial_boot_device.InstallApk('hello_world.apk')
    unified_launcher._StopDeviceAndOutputState(initial_boot_device, '/foobar')
    initial_boot_device.CleanUp()

    self.mox.ReplayAll()

    unified_launcher._FirstBootAtBuildTimeOnly(
        [
            os.path.join(self._tempdir, 'system.img'),
            os.path.join(self._tempdir, 'userdata.img'),
            os.path.join(self._tempdir, 'build.prop')
        ],
        skin,
        133,
        memory,
        '/foobar',
        vm_size=36,
        source_properties=None,
        default_properties=None,
        qemu_gdb_port=0,
        enable_single_step=False,
        emulator_tmp_dir=None,
        boot_time_apks=['hello_world.apk'],
        mini_boot=False)

  def testBoot_snapshot(self):
    self.mox.StubOutClassWithMocks(emulated_device, 'EmulatedDevice')
    initial_boot_device = emulated_device.EmulatedDevice(
        android_platform=mox.IsA(emulated_device.AndroidPlatform),
        qemu_gdb_port=0,
        enable_single_step=False,
        source_properties=None,
        mini_boot=False,
        use_waterfall=False,
        forward_bin=None,
        ports_bin=None)
    self.mox.StubOutWithMock(initial_boot_device, 'Configure')
    self.mox.StubOutWithMock(initial_boot_device, 'StartDevice')
    skin = 'rabbit_fur'
    memory = '42'
    initial_boot_device.Configure(
        self._tempdir,
        skin,
        memory,
        133,
        36,
        source_properties=None,
        default_properties=None,
        kvm_present=mox.IsA(bool),
        system_image_path=os.path.join(self._tempdir, 'system.img'),
        data_image_path=os.path.join(self._tempdir, 'userdata.img'),
        vendor_img_path=None,
        encryptionkey_img_path=None,
        advanced_features_ini=None,
        build_prop_path=os.path.join(self._tempdir, 'build.prop'),
        data_files=[])

    initial_boot_device.StartDevice(enable_display=False,
                                    start_vnc_on_port=0,
                                    emulator_tmp_dir=None,
                                    save_snapshot=False,
                                    modified_ramdisk_path=None)

    self.mox.StubOutWithMock(initial_boot_device, 'KillEmulator')
    self.mox.StubOutWithMock(unified_launcher, '_StopDeviceAndOutputState')
    unified_launcher._StopDeviceAndOutputState(initial_boot_device,
                                               '/foobar')
    initial_boot_device.LogToDevice('Device booted.')
    initial_boot_device.CleanUp()

    self.mox.ReplayAll()

    unified_launcher._FirstBootAtBuildTimeOnly(
        [
            os.path.join(self._tempdir, 'system.img'),
            os.path.join(self._tempdir, 'userdata.img'),
            os.path.join(self._tempdir, 'build.prop')
        ],
        skin,
        133,
        memory,
        '/foobar',
        vm_size=36,
        source_properties=None,
        default_properties=None,
        qemu_gdb_port=0,
        enable_single_step=False,
        emulator_tmp_dir=None,
        boot_time_apks=[],
        mini_boot=False)

  def testConvertToDict(self):
    self.assertEquals(
        {'hello': 'world'},
        unified_launcher._ConvertToDict(['hello=world']))

  def testConvertToDict_nestedEquals(self):
    self.assertEquals(
        {'hello': 'world=5'},
        unified_launcher._ConvertToDict(['hello=world=5']))

  def testInfo_raw(self):
    with tempfile.NamedTemporaryFile() as proto_file:
      proto_file.write(self._test_proto.SerializeToString())
      proto_file.flush()
      output = StringIO()
      unified_launcher._PrintInfo(proto_file.name, 'raw', out=output)
      self.assertEquals(output.getvalue(),
                        self._test_proto.SerializeToString())

  def testInfo_text(self):
    with tempfile.NamedTemporaryFile() as proto_file:
      proto_file.write(self._test_proto.SerializeToString())
      proto_file.flush()
      output = StringIO()
      unified_launcher._PrintInfo(proto_file.name, 'text', out=output)
      self.assertTrue('skin: "800x900"' in output.getvalue(), output.getvalue())


def _CreateFile(path, payload=''):
  """Creates a file at path.

  Arguments:
    path: the path to create the file at.
    payload: [optional] the contents to store in the file.

  Returns:
    The path that was created.
  """
  with open(path, 'wb') as f:
    f.write(payload)
  return path


if __name__ == '__main__':
  googletest.main()
