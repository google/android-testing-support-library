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

"""Allows emulators to be booted ran/killed."""



import collections
import configparser
import json
import logging
import os
import io
import subprocess
import sys
import tempfile
import time



from absl import app
from absl import flags

from google.protobuf import text_format
from tools.android.emulator import resources

from tools.android.emulator import common
from tools.android.emulator import emulated_device
from tools.android.emulator import emulator_meta_data_pb2
from tools.android.emulator import reporting


FLAGS = flags.FLAGS
flags.DEFINE_enum('action', None,
                  ['boot', 'start', 'mini_boot', 'ping', 'kill', 'info'],
                  'The action to perform against the emulator images')
flags.DEFINE_string('skin', None, '[BOOT ONLY] The skin parameter to pass '
                    'to the emulator')
flags.DEFINE_string('density', None, '[bazel ONLY] Density of the lcd screen')
flags.DEFINE_string('cache', None, '[bazel ONLY] Size of cache partition in mb '
                    '- currently not functioning')
flags.DEFINE_string('vm_size', None, '[bazel ONLY] VM heap size in mb')
flags.DEFINE_integer('memory', None, '[bazel ONLY] the memory for the emulator')
flags.DEFINE_spaceseplist('system_images', None, '[bazel ONLY] the system '
                          'images to boot the emulator with')
flags.DEFINE_spaceseplist('apks', None, '[START ONLY] the apks to install')
flags.DEFINE_spaceseplist('system_apks', None, '[START ONLY] system apks to '
                          'install')
flags.DEFINE_boolean('preverify_apks', False, '[START ONLY] if true the apks '
                     'will be preverified upon install (normal production  '
                     'behaviour). It defaults to disabled because some of the '
                     'verification failures are overkill in the bazel '
                     'environment. For example it is very easy to have '
                     'both a test apk and app apk contain a ref to the '
                     'same class file (eg Maps from the guava jars) and '
                     'this kills the verifier (out of the fear that the '
                     '2 apks have different class definitions and the '
                     'optimizations in the app apk will bypass the test '
                     'apks class def causing confusion). Some tests may '
                     'also want to take advantage of redefining a prod '
                     'class to something test friendly. The incremental '
                     'deployment tool (Speedy) by its very nature needs '
                     'to have this turned off as well. If you need it turned '
                     'on, simply enable this flag')
flags.DEFINE_string('generate_output_dir', None, '[bazel ONLY] the '
                    'location to store generated images to.')
flags.DEFINE_boolean('single_image_file', True, '[bazel ONLY] deprecated '
                     'always true.')
flags.DEFINE_string('image_input_file', None, '[bazel ONLY] causes '
                    'the emulator to be started from a tarred and compressed '
                    'file with userdata images in it. (Use in conjuction with '
                    'single_image_file')
flags.DEFINE_integer('adb_server_port', None, '[START/KILL ONLY] the port to '
                     ' start or connect to ADB on.')
flags.DEFINE_integer('emulator_port', None, '[START/KILL ONLY] the port the '
                     'emulator admin terminal is on.')
flags.DEFINE_integer('adb_port', None, '[START/KILL ONLY] the port the '
                     'emulator will open to respond to adb requests to.')
flags.DEFINE_string('logcat_path', None, '[START ONLY] Store logcat files '
                    'here.')
flags.DEFINE_string('logcat_filter', None, '[START ONLY] Filter for logcat')
flags.DEFINE_boolean('take_snapshots', True, '[bazel ONLY] deprecated - always'
                     'true. generate a snapshot after manipulating the device.')
flags.DEFINE_enum('net_type', 'fastnet', ['edge', 'fastnet', 'gprs', 'gsm',
                                          'hscsd', 'hsdpa', 'umts', 'off'],
                  'The network type to use. Network speeds/delay depend '
                  'on network type')
flags.DEFINE_boolean('copy_system_images', False, '[bazel ONLY] deprecated'
                     'always false')
flags.DEFINE_boolean('flag_configured_android_tools', True, '[bazel ONLY] - '
                     'deprecated always true')
flags.DEFINE_string('android_launcher_tool', None, '[bazel ONLY] the path '
                    'to the android_launcher_tool.')
flags.DEFINE_string('android_corp_libs', None, '[bazel ONLY] deprecated '
                    'no longer used.')
flags.DEFINE_string('source_properties_file', None, '[bazel ONLY] the path to '
                    'a source.properties file for this system image')
flags.DEFINE_string('adb', None, 'The path to ADB')
flags.DEFINE_string(
    'custom_emulator', None, '[bazel ONLY] path to a custom '
    'emulator binary to launch')
flags.DEFINE_string(
    'emulator_x86', None, '[bazel ONLY] the path to '
    'emulator_x86. Deprecated, only used for qemu1 emulators.')
flags.DEFINE_string(
    'emulator_arm', None, '[bazel ONLY] the path to '
    'emulator_arm Deprecated, only used for qemu1 emulators.')
flags.DEFINE_string('adb_static', None, 'OBSOLETE: the path to adb.static')
flags.DEFINE_string('adb_turbo', None, 'The path to turbo adb')
flags.DEFINE_string('forward_bin', None, 'The path to h2o forwarder binary')
flags.DEFINE_string('adb_bin', None, '[DEPRECATED] do not use. '
                    'Will be removed within a couple releases. '
                    '--waterfall_bin should be used instead.')
flags.DEFINE_string('waterfall_bin', None, 'The path to the waterfall '
                    'adb binary')
flags.DEFINE_string('ports_bin', None, 'The path to h2o port forwarder binary')
flags.DEFINE_string('emulator_x86_static', None, 'Deprecated. NO-OP.')
flags.DEFINE_string('emulator_arm_static', None, 'Deprecated. NO-OP.')
flags.DEFINE_string('empty_snapshot_fs', None, '[bazel ONLY] the path to '
                    'a snapshot fs image')
flags.DEFINE_string('mksdcard', None, '[bazel ONLY] the path to '
                    'mksdcard')
flags.DEFINE_list('bios_files', None, '[bazel ONLY] a list of bios files for '
                  'the emulator.')
flags.DEFINE_list('broadcast_message', None, '[START ONLY] a list of strings '
                  'in the format key=value. These will be broadcast as extras '
                  'when the emulator is fully booted and after all apks have '
                  'been installed. They will be broadcast under the action '
                  'ACTION_MOBILE_NINJAS_START')
flags.DEFINE_string('emulator_metadata_path', None, '[bazel ONLY] the path to '
                    'a metadata protobuffer to start the emulator with.')
flags.DEFINE_string('export_launch_metadata_path', None, '[START ONLY] writes '
                    'emulator metadata after launch to this path')
flags.DEFINE_string('emulator_tmp_dir', None, 'Temporary directory where the '
                    'emulator sockets/system_images/ramdisk etc are placed when'
                    ' emulator is launched.')
flags.DEFINE_string('default_properties_file', None, '[bazel ONLY] the path to '
                    'device boot properties.')
flags.DEFINE_boolean('launch_in_seperate_session', True, '[START ONLY] causes '
                     'the launcher to start the emulator in a seperate '
                     'process group. Necessary for bazel run invocations '
                     'the emulator runs in the background and will be killed '
                     'otherwise. Test runners should always set this to FALSE')
flags.DEFINE_integer('window_scale', 100, 'Scale factor of emulator window in'
                     'percent.')
flags.DEFINE_string('initial_locale', 'en-US', 'The locale that would be set '
                    'during the emulator initialization.')
flags.DEFINE_string('initial_ime', 'com.android.inputmethod.latin/.LatinIME',
                    'The IME that would be set during the emulator '
                    'initialization.')
flags.DEFINE_boolean('with_audio', False, '[Start ONLY] connects the virtual'
                     'sound card to your local host audio system.')
flags.DEFINE_string('kvm_device', '/dev/kvm',
                    'The path to the /dev/kvm pseudo device.')
flags.DEFINE_integer('qemu_gdb_port', 0, 'If set - starts QEMU with gdbserver '
                     'listening on the provided port.')
flags.DEFINE_boolean('enable_single_step', False, 'Starts QEMU in singlestep '
                     'mode.')
flags.DEFINE_boolean('with_boot_anim', False, '[Start ONLY] Starts emulator '
                     'with boot animation.')
flags.DEFINE_list('extra_certs', None, '[Start ONLY] a list of certs to add to '
                  'the emulator\'s trusted CA certs.')
flags.DEFINE_list('emulator_extra_lib_dir', None, '[Boot / Start] a list of '
                  'dirs to prepend to the emulator\'s ld_library_path')
flags.DEFINE_enum('lockdown_level', None, ['no_adb', 'no_settings_control'],
                  '[Start ONLY] Lockdown level, higher level includes all '
                  'lower levels.')
flags.DEFINE_enum('output_format', 'text', ['text', 'raw'], '[info ONLY] the '
                  'format to use when printing the emulator metadata proto.')
flags.DEFINE_boolean('launch_in_xvfb', True, 'Deprecated. NO-OP. Always Xvfb.')
flags.DEFINE_boolean('enable_display', True, 'False asks not to render into '
                     'an externally set $DISPLAY. Default is True.')
flags.DEFINE_integer('start_vnc_on_port', 0, 'Starts VNC server on specified '
                     'port. Default value 0 asks not to start VNC.')
flags.DEFINE_enum('open_gl_driver', None, emulated_device.OPEN_GL_DRIVERS,
                  'The driver that will be used for open gl emulation. '
                 )
flags.DEFINE_boolean('allow_experimental_open_gl', False, 'If true, checks '
                     'that prevent open_gl_driver values with known issues '
                     'are disabled.')
add_insecure_cacert_default = (
    False)
flags.DEFINE_boolean('add_insecure_cacert', add_insecure_cacert_default,
                     '[Start Only] If true adds an insecure cacert to the list '
                     'of trusted certs (The CyberVillians cert.)')
flags.DEFINE_boolean('grant_runtime_permissions', True, 'Grant runtime '
                     'permissions while installing the apps on api level >= 23')
flags.DEFINE_boolean('ignore_apk_installation_failures', False,
                     'Ignore errors if we fail to install a apk')
flags.DEFINE_list('accounts', None, '[START ONLY] a list of strings in format '
                  'username:password. These accounts will be added to the '
                  'emulator when it is fully booted and after all apks '
                  'have been installed.')
# TODO: Change default to true after all teams migrate.
flags.DEFINE_boolean('enable_console_auth', False,
                     'Enable console port auth for security reason')
flags.DEFINE_boolean('enable_g3_monitor', True,
                     'Enable g3 monitor for android_test. This is not '
                     'supported for --action=mini_boot')
flags.DEFINE_boolean('enable_gps', True, 'Enable emulator gps simulation')
flags.DEFINE_integer('retry_attempts', 4,
                     'The retry count when transient failure happens.')
flags.DEFINE_bool('enable_gms_usage_reporting', False, 'Whether to show gms '
                  'usage reporting dialog')
flags.DEFINE_string('android_sdk_path', None,
                    'The path where android_sdk resides on the system.')
flags.DEFINE_list('platform_apks', None, '[BOOT ONLY] Platform apks are '
                  'installed once at boot time as opposed to --apks which are '
                  'installed every time the emulator starts.')
flags.DEFINE_string('sim_access_rules_file', None, 'the path to a sim access '
                    'rules proto file. Used to grant UICC carrier privileges '
                    'to apps.')
flags.DEFINE_string('phone_number', None, 'A custom phone number to set for '
                    'the emulator. Used for apps that need specific phone '
                    'numbers.')
flags.DEFINE_boolean('save_snapshot', False, 'If true, saves the device  '
                     'snapshots in BOOT phase and reloads them during START '
                     ' phase.')
flags.DEFINE_string('snapshot_file', None, '[bazel ONLY] The path to the '
                    'snapshot file that was saved in BOOT cycle and is used '
                    'during START cycle.')
flags.DEFINE_bool(
    'use_h2o', False, '[DEPRECATED] this flag will be removed within a couple '
    'releases. --use_waterfall should be used instead')
flags.DEFINE_bool(
    'use_waterfall', False, 'Whether to use waterfall to control the device. '
    'Note that if this option is true, turbo will not be '
    'configured.')

_METADATA_FILE_NAME = 'emulator-meta-data.pb'
_USERDATA_IMAGES_NAME = 'userdata_images.dat'
_RAM_BIN = 'ram.bin'

_ADD_ACCOUNT_BROADCAST_ACTION = ('com.google.android.apps.auth.test.support'
                                 '.action.ADD_ACCOUNT_WITH_PASSWORD')
_ADD_ACCOUNT_BOOLEAN_EXTRAS = {'syncable': True, 'auto_sync': True}


@flags.validator('window_scale')
def _CheckWindowScale(window_scale):
  if window_scale < 10 or window_scale > 300:
    raise flags.ValidationError('Scale factor outside range: 10-300')
  return True


def _FindSystemImagesDir(system_image_files):
  for f in system_image_files:
    if os.path.basename(f) in ('kernel-qemu', 'kernel-ranchu',
                               'kernel-ranchu-64'):
      return os.path.dirname(f)
  # Fall back to directory of 1st file.
  return os.path.dirname(system_image_files[0])


def _FirstBootAtBuildTimeOnly(
    system_images,
    skin,
    density,
    memory,
    output_dir,
    source_properties,
    vm_size,
    default_properties,
    qemu_gdb_port,
    enable_single_step,
    emulator_tmp_dir,
    boot_time_apks,
    mini_boot):
  """Performs first, cold boot of a system image.

  This is only used at build time. Blaze boots up device in this mode, then
  shuts it down; output of that build action are the disk images. First boot
  of the device is special, slow and weird so we don't want to do that at
  "runtime". Network and all other services are functional after second boot.

  ONLY in this mode we ignore all display and openGL related flags passed to
  unified_launcher (assumption is the bazel will never actually pass them:
  and ONLY in this mode we use the no-op rendering mode "emulator -no-window"
  because 1) it is enough for building the disk images and 2) we want to make
  buildtime action as lightweight and non-flaky as possible (which Xvfb is not)

  Args:
    system_images: a list of system images all in the same directory for boot
      and other support files.
    skin: the screen resolution of the emulated device.
    density: the pixel density.
    memory: the ram of the device.
    output_dir: where to place the fully booted images and snapshot.
    source_properties: an optional dictionary of source.properties for this
      system.
    vm_size: the vmheap per android application.
    default_properties: an optional dictionary of default boot properties to
      set.
    qemu_gdb_port: emulator will start with gdb debugging listening on the port
    enable_single_step: emulator will start in stopped state for debug
      session
    emulator_tmp_dir: temporary directory where system_images/sockets/ramdisk
      files are placed while starting the emulator
    boot_time_apks: apks to install at boot time.
    mini_boot: should the device be booted up in a minimalistic mode.
  """

  sysdir = _FindSystemImagesDir(system_images)
  sysimg_path = (
      _ExtractSuffixFile(system_images, 'system.img.tar.gz') or
      _ExtractSuffixFile(system_images, 'system.img'))
  dataimg_path = (_ExtractSuffixFile(system_images, 'userdata.img.tar.gz') or
                  _ExtractSuffixFile(system_images, 'userdata.img'))
  vendor_img_path = (_ExtractSuffixFile(system_images, 'vendor.img.tar.gz') or
                     _ExtractSuffixFile(system_images, 'vendor.img'))

  encryptionkey_img_path = _ExtractSuffixFile(system_images,
                                              'encryptionkey.img')
  advanced_features_ini = _ExtractSuffixFile(system_images,
                                             'advancedFeatures.ini')
  build_prop_path = _ExtractSuffixFile(system_images, 'build.prop')

  data_files = _ExtractDataFiles(system_images)

  # If this file is present, then the ramdisk was patched as part of a previous
  # build action and we can just use it as is.
  modified_ramdisk_path = _ExtractPrefixFile(system_images, 'modified_ramdisk_')

  device = emulated_device.EmulatedDevice(
      android_platform=_MakeAndroidPlatform(),
      qemu_gdb_port=qemu_gdb_port,
      enable_single_step=enable_single_step,
      source_properties=source_properties,
      mini_boot=mini_boot,
      use_waterfall=FLAGS.use_h2o or FLAGS.use_waterfall,
      forward_bin=FLAGS.forward_bin,
      ports_bin=FLAGS.ports_bin)
  device.delete_temp_on_exit = False  # we will do it ourselves.

  device.Configure(
      sysdir,
      skin,
      memory,
      density,
      vm_size,
      source_properties=source_properties,
      default_properties=default_properties,
      kvm_present=_IsKvmPresent(),
      system_image_path=sysimg_path,
      data_image_path=dataimg_path,
      vendor_img_path=vendor_img_path,
      encryptionkey_img_path=encryptionkey_img_path,
      advanced_features_ini=advanced_features_ini,
      build_prop_path=build_prop_path,
      data_files=data_files)

  device.StartDevice(enable_display=False,  # Will be ignored.
                     start_vnc_on_port=0,  # Will be ignored.
                     emulator_tmp_dir=emulator_tmp_dir,
                     save_snapshot=FLAGS.save_snapshot,
                     modified_ramdisk_path=modified_ramdisk_path)

  try:
    device.LogToDevice('Device booted.')
    for apk in boot_time_apks:
      try:
        device.InstallApk(apk)
      except Exception as error:
        device.KillEmulator()
        raise error
    # Wait for system being idle for 4 minutes before shutting down.
    if FLAGS.save_snapshot:
      idle = emulated_device.IdleStatus(device=device)
      for _ in range(40):
        time.sleep(6)
        load = idle.RecentMaxLoad(15)
        if load < 0.1:
          logging.info('Emulator is idle now.')
          break
    _StopDeviceAndOutputState(device, output_dir)
  finally:
    device.CleanUp()




def _StopDeviceAndOutputState(device, output_dir):
  """Takes a snapshot of the emulator and shuts down the device.

  The snapshot and image files are stored in output dir.

  Args:
    device: the emulated_device.EmulatedDevice to operate on.
    output_dir: the directory to write images to.
  """
  proto = device.GetEmulatorMetadataProto()
  ram_bin = None
  if FLAGS.save_snapshot:
    ram_bin = os.path.join(output_dir, _RAM_BIN)
    device.TakeSnapshot()
  device.KillEmulator(politely=True)
  device.StoreAndCompressUserdata(os.path.join(output_dir,
                                               _USERDATA_IMAGES_NAME),
                                  ram_bin)
  proto_file = open(os.path.join(output_dir, _METADATA_FILE_NAME), 'wb')
  proto_file.write(proto.SerializeToString())
  proto_file.flush()
  proto_file.close()


def _RestartDevice(device,
                   enable_display,
                   start_vnc_on_port,
                   net_type,
                   system_image_files,
                   input_image_file,
                   proto_filepath,
                   new_process_group=False,
                   window_scale=None,
                   with_audio=False,
                   with_boot_anim=False,
                   emulator_tmp_dir=None,
                   open_gl_driver=None,
                   experimental_open_gl=False,
                   snapshot_file=None):
  """Restarts a emulated device from persisted images and snapshots.

  Args:
    device: an unstarted emulated_device.EmulatedDevice
    enable_display: if true emulator starts in display mode
    start_vnc_on_port: if a port is specified, starts vnc server at that port.
    net_type: the type of network to use while starting the emulator.
    system_image_files: list of system image files and other support files.
    input_image_file: a tar gz archive of the userdata dir.
    proto_filepath: (optional) a path to the emulator metadata proto.
    new_process_group: (optional) launches emulator in a new process group.
    window_scale: (optional) Scale factor of emulator window in percent.
    with_audio: (optional) indicates the emulator should turn on audio.
    with_boot_anim: (optional) Enables boot animation.
    emulator_tmp_dir: temporary directory where system_images/sockets/ramdisk
      files are placed while starting the emulator
    open_gl_driver: (optional) name of opengl driver to use.
    experimental_open_gl: (optional) if true - disables many opengl checks
    snapshot_file: The path of snapshot file generated in boot phase.
  Raises:
    Exception: if the emulated device cannot be started.
  """
  assert proto_filepath and os.path.exists(proto_filepath), 'No metadata found!'

  system_images_dir = _FindSystemImagesDir(system_image_files)

  proto_file = open(proto_filepath, 'rb')
  proto = emulator_meta_data_pb2.EmulatorMetaDataPb()
  proto.ParseFromString(proto_file.read())
  proto_file.close()

  assert not (proto.with_kvm and not _IsKvmPresent()), (
      'Try to run snapshot images with KVM support on non-KVM machine.')

  if 'x86' == proto.emulator_architecture:
    if not _IsKvmPresent():
      print('')
      print('=' * 80)
      print('= By activating KVM on your local host you can increase the '
             'speed of the emulator.      =')
      print('=' * 80)
    elif not proto.with_kvm:
      print('')
      print('=' * 80)
      print('= Please add --no to your bazel command line, to create '
             'snapshot images   =')
      print('= local with KVM support. This will increase the speed of the '
             'emulator.        =')
      print('=' * 80)
  else:
    print('')
    print('=' * 80)
    print('= By using x86 with KVM on your local host you can increase the '
           'speed of the emulator.')
    print('=' * 80)

  proto.system_image_dir = system_images_dir
  sysimg = (
      _ExtractSuffixFile(system_image_files, 'system.img.tar.gz') or
      _ExtractSuffixFile(system_image_files, 'system.img'))
  dataimg = (_ExtractSuffixFile(system_image_files, 'userdata.img.tar.gz') or
             _ExtractSuffixFile(system_image_files, 'userdata.img'))
  vendorimg_path = (_ExtractSuffixFile(system_image_files, 'vendor.img.tar.gz')
                    or _ExtractSuffixFile(system_image_files, 'vendor.img'))
  encryptionkeyimg_path = _ExtractSuffixFile(system_image_files,
                                             'encryptionkey.img')
  advanced_features_ini = _ExtractSuffixFile(system_image_files,
                                             'advancedFeatures.ini')
  build_prop_path = _ExtractSuffixFile(system_image_files, 'build.prop')

  data_files = _ExtractDataFiles(system_image_files)

  # TODO: Move data to another field in the proto.
  images_dict = device.BuildImagesDict(sysimg, dataimg, vendorimg_path,
                                       encryptionkeyimg_path,
                                       advanced_features_ini, build_prop_path,
                                       data_files)
  proto.system_image_path = json.dumps(images_dict)
  device._metadata_pb = proto
  device.StartDevice(enable_display,
                     start_vnc_on_port=start_vnc_on_port,
                     net_type=net_type,
                     userdata_tarball=input_image_file,
                     new_process_group=new_process_group,
                     window_scale=window_scale,
                     with_audio=with_audio,
                     with_boot_anim=with_boot_anim,
                     emulator_tmp_dir=emulator_tmp_dir,
                     open_gl_driver=open_gl_driver,
                     allow_experimental_open_gl=experimental_open_gl,
                     save_snapshot=FLAGS.save_snapshot,
                     snapshot_file=snapshot_file)


def _TryInstallApks(device, apks, grant_runtime_permissions):
  for apk in apks:
    try:
      device.InstallApk(apk,
                        grant_runtime_permissions=grant_runtime_permissions)
    except Exception as error:
      if FLAGS.ignore_apk_installation_failures:
        logging.warning('Failed installing apk -' + apk + '- Continuing ...')
        continue
      device.KillEmulator()
      raise error


def _Run(adb_server_port,
         emulator_port,
         adb_port,
         enable_display,
         start_vnc_on_port,
         logcat_path,
         logcat_filter,
         system_images,
         input_image_file,
         emulator_metadata_path,
         apks,
         system_apks,
         net_type,
         export_launch_metadata_path=None,
         preverify_apks=False,
         new_process_group=False,
         window_scale=None,
         broadcast_message=None,
         initial_locale=None,
         initial_ime=None,
         with_audio=False,
         qemu_gdb_port=0,
         enable_single_step=False,
         with_boot_anim=False,
         extra_certs=None,
         emulator_tmp_dir=None,
         lockdown_level=None,
         open_gl_driver=None,
         experimental_open_gl=False,
         add_insecure_cert=False,
         grant_runtime_permissions=True,
         accounts=None,
         reporter=None,
         mini_boot=False,
         sim_access_rules_file=None,
         phone_number=None):
  """Starts a device for use or testing.

  Args:
    adb_server_port: the adb server port to connect to
    emulator_port: the port the emulator will use for its telnet interface
    adb_port: the port the emulator will accept adb connections on
    enable_display: true the emulator starts with display, false otherwise.
    start_vnc_on_port: if a port is specified, starts vnc server at that port.
    logcat_path: the directory to store logcat data to (None implies no logcat)
    logcat_filter: the filter to apply to logcat (None implies no logcat)
    system_images: system images to restart with (only valid for bazel created
      start scripts).
    input_image_file: a compressed file containing userdata files. (only valid
      for bazel start scripts)
    emulator_metadata_path: the path to the emulator_metadata_pb. (Only valid
      for bazel start scripts)
    apks: extra apks to install on the started device.
    system_apks: apks to install onto the /system partition.
    net_type: The type of network to use.
    export_launch_metadata_path: optional path to write the emulator metadata
      to.
    preverify_apks: install apks onto the emulator with verification turned on
      defaults to false.
    new_process_group: emulator will be in a seperate process group (default
      false)
    window_scale: Scale factor of emulator window in percent.
    broadcast_message: A dict of key value extras to broadcast at launch.
    initial_locale: A locale, that would be send through broadcasting message
      and set as a locale on an android device if possible.
    initial_ime: The ime, that would be send through broadcasting message
      and set as an ime on an android device if possible.
    with_audio: Indicates the emulator should output audio to the local sound
      card.
    qemu_gdb_port: Start the gdb stub in qemu listening on this port
    enable_single_step: Start qemu in single step mode.
    with_boot_anim: Enables boot animation.
    extra_certs: additional CA certs to install.
    emulator_tmp_dir: Temporary directory where files are placed when the
      emulator is launched
    lockdown_level: Lockdown level, higher level includes all lower levels.
    open_gl_driver: [optional] specifies the driver to use for OpenGL.
    experimental_open_gl: [optional] disables sanity checks for OpenGL.
    add_insecure_cert: [optional] bool: adds the cyber villians cert.
    grant_runtime_permissions: [optional] bool: Grant runtime permissions while
    installing apk on api level > 23.
    accounts: A list of accounts to be added to emulator at launch.
    reporter: a reporting.Reporter to track the emulator state.
    mini_boot: should the device be booted up in a minimalistic mode.
    sim_access_rules_file: sim access rules textproto filepath.
    phone_number: custom phone number to on the sim.
  """
  device = emulated_device.EmulatedDevice(
      android_platform=_MakeAndroidPlatform(),
      adb_server_port=adb_server_port,
      emulator_telnet_port=emulator_port,
      emulator_adb_port=adb_port,
      qemu_gdb_port=qemu_gdb_port,
      enable_single_step=enable_single_step,
      logcat_path=logcat_path,
      logcat_filter=logcat_filter,
      enable_console_auth=FLAGS.enable_console_auth,
      enable_g3_monitor=FLAGS.enable_g3_monitor,
      enable_gps=FLAGS.enable_gps,
      add_insecure_cert=add_insecure_cert,
      reporter=reporter,
      mini_boot=mini_boot,
      sim_access_rules_file=sim_access_rules_file,
      phone_number=phone_number,
      source_properties=_ReadSourceProperties(FLAGS.source_properties_file),
      use_waterfall=FLAGS.use_h2o or FLAGS.use_waterfall,
      forward_bin=FLAGS.forward_bin,
      ports_bin=FLAGS.forward_bin)

  _RestartDevice(
      device,
      enable_display=enable_display,
      start_vnc_on_port=start_vnc_on_port,
      net_type=net_type,
      system_image_files=system_images,
      input_image_file=input_image_file,
      proto_filepath=emulator_metadata_path,
      new_process_group=new_process_group,
      window_scale=window_scale,
      with_audio=with_audio,
      with_boot_anim=with_boot_anim,
      emulator_tmp_dir=emulator_tmp_dir,
      open_gl_driver=open_gl_driver,
      experimental_open_gl=experimental_open_gl,
      snapshot_file=FLAGS.snapshot_file)

  device.SyncTime()
  if mini_boot:
    return

  if preverify_apks:
    device.PreverifyApks()

  if system_apks:
    device.InstallSystemApks(system_apks)

  gmscore_apks = None
  if apks:
    gmscore_apks = [apk for apk in apks if 'gmscore' in apk.lower()]
    other_apks = [apk for apk in apks if apk not in gmscore_apks]
    _TryInstallApks(device, other_apks, grant_runtime_permissions)

  if export_launch_metadata_path:
    proto = device.GetEmulatorMetadataProto()
    with open(export_launch_metadata_path, 'wb') as proto_file:
      proto_file.write(proto.SerializeToString())

  if add_insecure_cert:
    device.InstallCyberVillainsCert()
  if extra_certs:
    for cert in extra_certs:
      device.AddCert(cert)
  if initial_locale is not None:
    broadcast_message['initial_locale'] = initial_locale
  if initial_ime is not None:
    broadcast_message['initial_ime'] = initial_ime

  # send broadcast ACTION_MOBILE_NINJAS_START to the device
  device.BroadcastDeviceReady(broadcast_message)

  if adb_server_port:
    device.ConnectDevice()

  if lockdown_level:
    device.Lockdown(lockdown_level)

  if accounts:
    for account in accounts:
      account_name, password = account.split(':', 1)
      account_extras = collections.OrderedDict()
      account_extras['account_name'] = account_name
      account_extras['password'] = password
      account_extras.update(_ADD_ACCOUNT_BOOLEAN_EXTRAS)
      device.BroadcastDeviceReady(account_extras,
                                  _ADD_ACCOUNT_BROADCAST_ACTION)


  if gmscore_apks:
    _TryInstallApks(device, gmscore_apks, grant_runtime_permissions)


def _Kill(adb_server_port, emulator_port, adb_port):
  """Shuts down an emulator using the telnet interface."""
  device = emulated_device.EmulatedDevice(
      adb_server_port=adb_server_port,
      emulator_telnet_port=emulator_port,
      emulator_adb_port=adb_port,
      android_platform=_MakeAndroidPlatform())
  device._pipe_traversal_running = True  # pylint: disable=protected-access
  device.KillEmulator()


def _Ping(adb_server_port, emulator_port, adb_port):
  """Ensures device is running."""
  device = emulated_device.EmulatedDevice(
      adb_server_port=adb_server_port,
      emulator_telnet_port=emulator_port,
      emulator_adb_port=adb_port,
      device_serial='localhost:%s' % adb_port,
      android_platform=_MakeAndroidPlatform())
  device._pipe_traversal_running = True  # pylint: disable=protected-access
  assert device.Ping() or device.Ping() or device.Ping()


def _ReadSourceProperties(source_properties_file):
  return _TryToConvertIniStyleFileToDict(source_properties_file)


def _TryToConvertIniStyleFileToDict(ini_style_file):
  if ini_style_file:
    with open(ini_style_file) as real_text_handle:
      text = real_text_handle.read()
      filehandle = io.StringIO(u'[android]\n' + text)
      try:
        config = configparser.ConfigParser()
        config.readfp(filehandle)
        return dict(config.items('android'))
      finally:
        filehandle.close()

  else:
    return None


def _ReadDefaultProperties(default_properties_file):
  if default_properties_file:
    return _TryToConvertIniStyleFileToDict(default_properties_file)
  return None


def _ExtractSystemImages(overloaded_flag):
  return [si for si in overloaded_flag
          if 'pregen' not in si and not si.endswith('.apk')]


def _ExtractBootTimeApks(overloaded_flag):
  return [boot_apk for boot_apk in overloaded_flag
          if boot_apk.endswith('.apk')]


def _ExtractSuffixFile(overloaded_flag, suffix):
  if overloaded_flag:
    for f in overloaded_flag:
      if f.endswith(suffix):
        return f
  return None


def _ExtractDataFiles(files):
  output = []
  for f in files:
    if 'data/' in f:
      output.append(f)
  return output


def _ExtractPrefixFile(overloaded_flag, prefix):
  if overloaded_flag:
    for f in overloaded_flag:
      if os.path.basename(f).startswith(prefix):
        return f
  return None


def _RemoveBootTimeApks(boot_apks=None, other_apks=None):
  """Removes any apks which were installed at boot time from a list of apks."""
  boot_apks = _HashFiles(boot_apks)
  if boot_apks:
    other_apks = _HashFiles(other_apks)
    for file_hash, _ in boot_apks.iteritems():
      if file_hash in other_apks:
        del other_apks[file_hash]
    return other_apks.values()
  else:
    return other_apks


def _HashFiles(files):
  """Hashes a list of files.

  Arguments:
    files: a list of file paths.

  Returns:
    An ORDERED dictionary of hashes to files. Items in the dictionary appear in
    the same order as the files inputed to the method.
  """
  if not files:
    return {}

  hashes_to_files = collections.OrderedDict()
  for line in subprocess.check_output(['sha1sum'] + files).split('\n'):
    if line:
      hash_and_file = line.split()
      hashes_to_files[hash_and_file[0]] = hash_and_file[1]
  return hashes_to_files


def _GetTmpDir():
  """Determines the right temporary dir to use and creates it if necessary.

  Returns:
    path to temporary directory.
  """
  tmp_dir = FLAGS.emulator_tmp_dir or os.path.abspath(
      tempfile.mkdtemp('android-emulator-launch'))

  if not os.path.exists(tmp_dir):
    os.makedirs(tmp_dir)
  return tmp_dir


def EntryPoint(reporter):
  """Determines the action to take based on flags.

  This is used both by the main() method and by scripts generated by legacy
  genrules.

  Arguments:
    reporter: A reporting.Reporter to track the invocation.

  Raises:
    Exception: If the action flag value is unknown.
  """
  # ignore the pregenerated folder in many system image file groups.
  filtered_system_images = _ExtractSystemImages(FLAGS.system_images)
  # TODO(b/33889561)
  boot_time_apks = _ExtractBootTimeApks(FLAGS.system_images)
  if FLAGS.platform_apks:
    boot_time_apks.extend(FLAGS.platform_apks)
  start_time_apks = _RemoveBootTimeApks(boot_time_apks, FLAGS.apks)

  # Ensure the files needed are cached.
  _EnsureFilesCached([
      filtered_system_images,
      boot_time_apks,
      FLAGS.apks,
      FLAGS.system_apks,
      FLAGS.image_input_file,
      FLAGS.bios_files,
      FLAGS.android_launcher_tool,
      FLAGS.android_corp_libs,
      FLAGS.source_properties_file,
      FLAGS.adb,
      FLAGS.custom_emulator,
      FLAGS.emulator_x86,
      FLAGS.emulator_arm,
      FLAGS.adb_static,
      FLAGS.empty_snapshot_fs,
      FLAGS.mksdcard,
      FLAGS.bios_files,
      FLAGS.emulator_metadata_path,
      FLAGS.default_properties_file,
  ])

  src_props = _ReadSourceProperties(FLAGS.source_properties_file)

  assert emulated_device.API_LEVEL_KEY in src_props, ('API level not in source'
                                                      ' props file.')
  assert emulated_device.SYSTEM_ABI_KEY in src_props, ('ABI not in source'
                                                       ' props file.')

  mini_boot = FLAGS.action == 'mini_boot' or (
      # force mini_boot for arm images. They are unusable under full mode.
      int(src_props[emulated_device.API_LEVEL_KEY]) > 19 and
      src_props[emulated_device.SYSTEM_ABI_KEY].startswith('arm'))

  if 'boot' == FLAGS.action:
    _FirstBootAtBuildTimeOnly(
        filtered_system_images,
        FLAGS.skin,
        FLAGS.density,
        FLAGS.memory,
        FLAGS.generate_output_dir,
        src_props,
        FLAGS.vm_size,
        _ReadDefaultProperties(FLAGS.default_properties_file),
        FLAGS.qemu_gdb_port,
        FLAGS.enable_single_step,
        _GetTmpDir(),
        boot_time_apks,
        mini_boot)
  elif FLAGS.action in ['start', 'mini_boot']:
    _Run(FLAGS.adb_server_port, FLAGS.emulator_port, FLAGS.adb_port,
         FLAGS.enable_display, FLAGS.start_vnc_on_port, FLAGS.logcat_path,
         FLAGS.logcat_filter, filtered_system_images, FLAGS.image_input_file,
         FLAGS.emulator_metadata_path, start_time_apks, FLAGS.system_apks,
         FLAGS.net_type, FLAGS.export_launch_metadata_path,
         FLAGS.preverify_apks, FLAGS.launch_in_seperate_session,
         FLAGS.window_scale,
         _ConvertToDict(FLAGS.broadcast_message), FLAGS.initial_locale,
         FLAGS.initial_ime, FLAGS.with_audio, FLAGS.qemu_gdb_port,
         FLAGS.enable_single_step, FLAGS.with_boot_anim, FLAGS.extra_certs,
         _GetTmpDir(), FLAGS.lockdown_level, FLAGS.open_gl_driver,
         FLAGS.allow_experimental_open_gl, FLAGS.add_insecure_cacert,
         FLAGS.grant_runtime_permissions, FLAGS.accounts, reporter,
         mini_boot, FLAGS.sim_access_rules_file, FLAGS.phone_number)
  elif 'kill' == FLAGS.action:
    _Kill(FLAGS.adb_server_port, FLAGS.emulator_port, FLAGS.adb_port)
  elif 'ping' == FLAGS.action:
    _Ping(FLAGS.adb_server_port, FLAGS.emulator_port, FLAGS.adb_port)
  elif 'info' == FLAGS.action:
    _PrintInfo(FLAGS.emulator_metadata_path, FLAGS.output_format)
  else:
    raise Exception('Unhandled action: %s' % FLAGS.action)


def _PrintInfo(metadata_path, output_format, out=None):
  """Prints out the info contained in the metadata protobuf."""
  out = out or sys.stdout

  proto = emulator_meta_data_pb2.EmulatorMetaDataPb()
  with open(metadata_path, 'rb') as proto_file:
    proto.ParseFromString(proto_file.read())

  if output_format == 'text':
    text_format.PrintMessage(proto, out, indent=2)
  else:
    out.write(proto.SerializeToString())


def _ConvertToDict(key_vals):
  if not key_vals:
    return {}
  else:
    result = {}
    for key_val in key_vals:
      assert '=' in key_val, 'Not in key val format: %s' % key_val
      key, val = key_val.split('=', 1)
      result[key] = val
    return result


def _MakeAndroidPlatform():
  """Pulls together all the binary tools / libs we'll be using today."""
  platform = emulated_device.AndroidPlatform()
  runfiles_base = os.environ.get(
      'TEST_SRCDIR') or os.environ.get('DEVICE_RUNFILES')
  if not runfiles_base:
    logging.warning('Cannot find runfiles (via env vars). Defaulting to $CWD.')
    runfiles_base = os.getcwd()
  else:
    runfiles_base = os.path.abspath(runfiles_base)

  if FLAGS.emulator_extra_lib_dir:
    for lib_dir in FLAGS.emulator_extra_lib_dir:
      assert os.path.exists(lib_dir), '%s: does not exist' % lib_dir
    platform.prepended_library_path = ':'.join(
        FLAGS.emulator_extra_lib_dir)

  root_dir = os.path.join(runfiles_base, 'android_test_support')
  # Sometimes we are running from android_test_support, fix root_dir here.
  if not os.path.exists(root_dir):
    root_dir = runfiles_base

  if FLAGS.custom_emulator:
    base_emulator_path = os.path.dirname(
        os.path.join(root_dir, FLAGS.custom_emulator))
  elif FLAGS.emulator_x86:
    base_emulator_path = os.path.dirname(os.path.join(root_dir,
                                                      FLAGS.emulator_x86))
  else:
    base_emulator_path = os.path.join(root_dir, (
        'third_party/java/android/android_sdk_linux/tools'))

  if not os.path.exists(base_emulator_path):
    logging.error('emulator tools dir %s does not exist.', base_emulator_path)
    sys.exit(1)
  if FLAGS.android_sdk_path:
    platform.android_sdk = FLAGS.android_sdk_path

  platform.base_emulator_path = base_emulator_path

  adb_path = None
  if FLAGS.use_h2o or FLAGS.use_waterfall:
    if FLAGS.adb_bin or FLAGS.waterfall_bin:
      adb_path = FLAGS.adb_bin or FLAGS.waterfall_bin
    else:
      adb_path = (''
                  'tools/android/emulator/support/waterfall/waterfall_bin')
  else:
    if FLAGS.adb_turbo:
      adb_path = FLAGS.adb_turbo
    else:
      adb_path = (''
                  'tools/android/emulator/support/adb.turbo')

  g3_relative = os.path.join('android_test_support', adb_path)
  if os.path.exists(adb_path):
    platform.adb = os.path.abspath(adb_path)
  elif os.path.exists(g3_relative):
    platform.adb = os.path.abspath(g3_relative)
  else:
    platform.adb = resources.GetResourceFilename(g3_relative)

  assert os.path.exists(platform.adb), ('%s: does not exist. please pass '
                                        '--adb_turbo' % platform.adb)
  platform.real_adb = FLAGS.adb

  if FLAGS.flag_configured_android_tools:
    platform.launcher_tool = FLAGS.android_launcher_tool
    platform.emulator_x86 = FLAGS.emulator_x86
    platform.emulator_arm = FLAGS.emulator_arm
    if FLAGS.kvm_device:
      platform.kvm_device = FLAGS.kvm_device
    platform.empty_snapshot_fs = FLAGS.empty_snapshot_fs
    platform.mksdcard = FLAGS.mksdcard
    platform.bios_files = filter(lambda e: e, FLAGS.bios_files)

  if FLAGS.custom_emulator:
    platform.emulator_wrapper_launcher = FLAGS.custom_emulator
  else:
    platform.emulator_wrapper_launcher = _ExtractSuffixFile(
        FLAGS.system_images, '/emulator')

  return platform


def _IsKvmPresent():
  """We only can use KVM is we have access to it."""
  kvm_device = _MakeAndroidPlatform().kvm_device
  kernel_module = os.access('/sys/class/misc/kvm/dev', os.R_OK)
  device_node = os.access(kvm_device, os.R_OK | os.W_OK)
  if not kernel_module:
    print('KVM Kernel module not readable.')
  if not device_node:
    print('%s: not readable or writable by current user' % kvm_device)

  return device_node and kernel_module


def _EnsureFilesCached(pathss):
  """Ensures file path(s) are cached.

  Args:
    pathss: A list where each element can be a str or a list of str.
  """
  for paths in pathss:
    if paths:
      if type(paths) is str:
        paths = (paths,)
      for path in paths:
        if path and type(path) is str:
          common.EnsureFileCached(path)


def main(unused_argv):
  logging.basicConfig(
      level=logging.INFO,
      format=('%(levelname).1s%(asctime)s.%(msecs)03d %(filename)s:%(lineno)s]'
              '%(message)s'),
      datefmt='%m%d %H:%M:%S')

  logging.debug('args: %s', ' '.join(sys.argv))
  attempts = 0
  reporter = reporting.MakeReporter()
  try:
    while True:
      attempts += 1
      try:
        EntryPoint(reporter)
        break
      except emulated_device.TransientEmulatorFailure as e:
        if attempts < FLAGS.retry_attempts:
          logging.warning('Transient failure: %s', e)
        else:
          reporter.ReportFailure('tools.android.emulator.SoMuchDeath',
                                 {'message': str(e),
                                  'attempts': attempts})
          logging.error('Tried %s times - failure: %s', attempts, e)
          raise e
  finally:
    reporter.Emit()
  logging.debug('return from main function.')


def AddFileLogHandler():
  """Adds a handler to the root logger which writes to a temp file."""
  with tempfile.NamedTemporaryFile(
      prefix='unified_launcher_',
      suffix='.log',
      delete=False) as f:
    log_filename = f.name
  file_handler = logging.FileHandler(log_filename)
  root_logger = logging.getLogger()
  root_logger.addHandler(file_handler)


if __name__ == '__main__':
  AddFileLogHandler()
  app.run(main)
