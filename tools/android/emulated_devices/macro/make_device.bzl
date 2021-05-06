"""Helper functions for generating android_device rules."""

load(
    "//tools/android/emulated_devices:macro/image.bzl",
    "image_api",
    "image_arch",
    "image_compressed_suffix",
    "image_device_visibility",
    "image_flavor",
    "image_version_string",
)

load(
    "//tools/android/emulated_devices:macro/emulator.bzl",
    "emulator_default_visibility",
    "emulator_is_qemu2",
    "emulator_suffix",
    "emulator_tags",
    "supported_arches_for_api",
)
load(
    "//tools/android/emulated_devices:macro/hardware.bzl",
    "hardware_device_attributes",
    "hardware_screen_density",
    "hardware_vm_heap",
    "new_hardware",
)
load("//tools/android/emulated_devices:macro/props.bzl", "new_props")
load("//tools/android/emulated_devices:macro/image_info.bzl", "API_TO_IMAGES")
load(
    "//tools/android/emulated_devices:macro/emulator_info.bzl",
    "QEMU",
    "QEMU2",
    "TYPE_TO_EMULATOR",
    "extra_system_image_contents",
)
load(
    "//tools/android/emulated_devices:macro/props_info.bzl",
    "generate_prop_content",
)

def _property_writer_impl(ctx):
    """Outputs a property file."""
    output = ctx.outputs.out
    ctx.actions.write(output = output, content = ctx.attr.content)

_property_writer = rule(
    attrs = {"content": attr.string()},
    outputs = {"out": "%{name}.properties"},
    implementation = _property_writer_impl,
)

HEAP_GROWTH_LIMIT = "dalvik.vm.heapgrowthlimit"

G3_ACTIVITY_CONTROLLER = "//tools/android/emulator:daemon/g3_activity_controller.jar"

_ENABLE_SNAPSHOT = True
_DISABLE_SNAPSHOT = False

def make_device(
        name,
        horizontal_resolution,
        vertical_resolution,
        screen_density,
        ram,
        vm_heap,
        cache,
        min_api,
        max_api = None,
        system_image_flavors = None,
        boot_properties = None,
        avd_properties = None,
        boot_apks = None,
        visibility = None,
        emulator_types = None,
        default_emulator_type = None,
        archs_override = None,
        api_to_images = API_TO_IMAGES):
    """Generates a set of android_device targets.

    It is recommended to use new_devices() instead.

    Arguments:
      name: Unused - SORRY!
      horizontal_resolution: see android_device.
      vertical_resolution: see android_device.
      screen_density: see android_device.
      ram: see android_device.
      vm_heap: see android_device.
      cache: see android_device.
      min_api: the minimum api to generate.
      max_api: [optional] the maximum api level to generate.
      system_image_flavors: a list of flavours to generate from. Flavours are
        defined in //tools/android/emulated_devices/macro/image_info
      boot_properties: [optional] A dictionary containing system propreties.
      avd_properties: [optional] A dictionary containing avd propreties.
      boot_apks: [optional] a list of apks to install on the emulator during the
        boot cycle.
      visibility: [optional] visibility to assign the android_device objects.
      emulator_types: [optional] A list of emulator types to generate.
      default_emulator_type: [optional] the emulator type to treat as the
        default. Extra targets will be generated for this emulator type with no
        explicit emulator suffix.
      archs_override: [optional] overrides any restrictions a particular emulator
        may define about running a particular architecture. (For example, we do
        not use QEMU to run ARM images after api level 19, but if you want to
        deal with the inheritant issues, feel free!
      api_to_images: [optional] a dictionary between api-level (int) and possible new_image instances.

      Returns:
        A set of all types of android_devices that meet the specified
        requirements.
    """
    name = name  # unused argument - historical reasons :(
    emulators = None
    if emulator_types:
        emulators = [TYPE_TO_EMULATOR[et] for et in emulator_types]
    default_emulator = None
    if default_emulator_type:
        default_emulator = TYPE_TO_EMULATOR[default_emulator_type]

    new_devices(
        name = "",
        hardware = new_hardware(
            horizontal_resolution,
            vertical_resolution,
            screen_density,
            ram,
            vm_heap,
            cache,
        ),
        min_api = min_api,
        max_api = max_api,
        user_props = new_props(
            boot_properties = boot_properties,
            avd_properties = avd_properties,
        ),
        boot_apks = boot_apks,
        emulators = emulators,
        default_emulator = default_emulator,
        archs_override = archs_override,
        system_image_flavors = system_image_flavors,
        visibility = visibility,
        api_to_images = api_to_images,
    )

def _make_property_target(
        name,
        user_props,
        emulator,
        emu_suffix,
        hardware,
        image):
    _property_writer(
        name = _get_property_target_name(name, emu_suffix, image),
        content = generate_prop_content(user_props, emulator, hardware, image),
        visibility = ["//visibility:private"],
    )

def _get_property_target_name(name, emu_suffix, image):
    return "%s_%s_%s_%s%s%s_props" % (
        name,
        image_flavor(image),
        image_version_string(image),
        image_arch(image),
        emu_suffix,
        image_compressed_suffix(image),
    )

def _make_system_image_target(name, contents):
    target_name = "%s_images" % name
    native.filegroup(
        name = target_name,
        srcs = contents,
        visibility = ["//visibility:private"],
    )
    return ":%s" % target_name

def new_devices(
        name,
        hardware,
        min_api,
        max_api = None,
        system_image_flavors = None,
        user_props = None,
        emulators = None,
        default_emulator = None,
        boot_apks = None,
        visibility = None,
        archs_override = None,
        api_to_images = API_TO_IMAGES):
    """Generates a set of android_device targets.

    Arguments:
      name: a name to prefix devices with.
      hardware: A dictionary containing hardware attributes. Create with
        //tools/android/emulated_devices/macro/hardware:new_hardware.
      min_api: the minimum api to generate.
      max_api: [optional] the maximum api level to generate.
      system_image_flavors: a list of flavours to generate from. Flavours are
        defined in //tools/android/emulated_devices/macro/image_info
      user_props: [optional] A dictionary containing system and avd properties.
        Create with //tools/android/emulated_devices/macro/props:new_props.
        Elements in this dictionary will override any properties defined by the
        system image or hardware themselves. The emulator may override certain
        user specified properties though.
      emulators: [optional] A list of emulator binaries to run the device on
        use the constants defined in
        //tools/android/emulated_devices/macro/emulator_info
      default_emulator: [optional] the emulator to treat as the default. Extra
        targets will be generated for this emulator type with no explicit emulator
        suffix.
      boot_apks: [optional] a list of apks to install on the emulator during the
        boot cycle.
      visibility: [optional] visibility to assign the android_device objects.
      archs_override: [optional] overrides any restrictions a particular emulator
        may define about running a particular architecture. (For example, we do
        not use QEMU to run ARM images after api level 19, but if you want to
        deal with the inheritant issues, feel free!
      api_to_images: [optional] a dictionary between api-level (int) and possible new_image instances.
      Returns:
        A set of all types of android_devices that meet the specified
        requirements.
    """

    user_props = user_props or new_props()
    if not emulators:
        emulators = [QEMU, QEMU2]

    boot_apks = boot_apks or []
    requested_flavors = system_image_flavors or ["google", "android"]

    images_to_build = []
    for api, images in sorted(api_to_images.items()):
        if max_api and api > max_api:
            continue
        if api < min_api:
            continue
        images_to_build.extend(images)


    for image in images_to_build:
        api = image_api(image)
        if image_flavor(image) not in requested_flavors:
            continue

        for emulator in emulators:
            arches = archs_override or supported_arches_for_api(emulator, api)
            if image_arch(image) not in arches:
                continue

            emu_suffix = emulator_suffix(emulator)
            actual_visibility = visibility or emulator_default_visibility(emulator)
            _make_property_target(
                name,
                user_props,
                emulator,
                emu_suffix,
                hardware,
                image,
            )
            _new_devices_for_image_and_emulator(
                name,
                hardware,
                boot_apks,
                actual_visibility,
                image,
                emulator,
                emu_suffix,
            )

        # Add an extra set of devices with no emulator suffix. They will use the
        # default emulator.
        default_emulator_for_image = default_emulator or _fallback_default_emulator_for_image(image, emulators, archs_override)

        if default_emulator_for_image:
            default_arches = archs_override or supported_arches_for_api(default_emulator_for_image, api)
            if image_arch(image) not in default_arches:
                continue

            # Default emulator targets should default to being public, even if the
            # implicit emulator is not, so that we can change the default emulator
            # without breaking visibility.
            actual_visibility = visibility or ["//visibility:public"]
            _make_property_target(
                name,
                user_props,
                default_emulator_for_image,
                "",
                hardware,
                image,
            )
            _new_devices_for_image_and_emulator(
                name,
                hardware,
                boot_apks,
                actual_visibility,
                image,
                default_emulator_for_image,
                "",
            )

def _new_devices_for_image_and_emulator(
        name,
        hardware,
        boot_apks,
        visibility,
        image,
        emulator,
        emu_suffix):
    tags = emulator_tags(emulator, image)
    property_target = ":" + _get_property_target_name(name, emu_suffix, image)
    system_image_contents = extra_system_image_contents(emulator, image)
    device_name = "%s_%s_%s%s%s" % (
        image_flavor(image),
        image_version_string(image),
        image_arch(image),
        emu_suffix,
        image_compressed_suffix(image),
    )

    if name:
        device_name = "%s_%s" % (name, device_name)

    # once boot_apks is a top level attribute, simplify this.
    system_image_target = _make_system_image_target(
        device_name,
        system_image_contents + boot_apks,
    )

    make_android_device(
        device_name,
        property_target,
        system_image_target,
        tags,
        image,
        visibility,
        hardware,
        emulator,
    )


def make_android_device(
        name,
        default_properties,
        system_image,
        tags,
        image,
        visibility,
        hardware,
        emulator):
    """Builds android_device targets."""

    native.android_device(
        name = name,
        default_properties = default_properties,
        system_image = system_image,
        tags = tags,
        visibility = image_device_visibility(image) or visibility,
        **hardware_device_attributes(hardware)
    )

def _fallback_default_emulator_for_image(image, emulators, archs_override):
    """The default emulator to fall back to if default_emulator is not specified.

    Args:
      image: the image of this device
      emulators: the list of emulators supported for this device
      archs_override: optional list of supported architectures

    Returns:
      The emulator to use as the default
    """

    # We normally default to qemu2, but we make an exception for wear 20-23,
    # which don't have ranchu support yet.
    if image_flavor(image) == "wear" and image_api(image) <= 23:
        preferred_emulators = [QEMU, QEMU2]
    else:
        preferred_emulators = [QEMU2, QEMU]

    for emulator in preferred_emulators:
        if _is_emulator_compatible(emulator, image, emulators, archs_override):
            return emulator
    return None

def _is_emulator_compatible(emulator, image, emulators, archs_override):
    """Checks whether a given emulator is compatible with a given image.

    In general, this is based on whether the emulator supports the image's
    architecture and API level, but there are a couple other factors to consider.
    If an 'archs_override' parameter was passed to new_devices, we should check
    for compatibility using that, rather than the supported architectures from
    the emulator config. Also if an 'emulators' list was passed to new_devices,
    we must reject the emulator if it's not in that whitelist.

    Args:
      emulator: The emulator to check for compatibility.
      image: The image to check against.
      emulators: The whitelist of supported emulators.
      archs_override: Optional list of supported architectures.

    Returns:
      Whether the emulator is compatible
    """

    if emulator not in emulators:
        return False

    supported_arches = archs_override or supported_arches_for_api(emulator, image_api(image))
    if image_arch(image) not in supported_arches:
        return False

    return True

