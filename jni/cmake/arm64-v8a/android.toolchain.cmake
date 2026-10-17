# The NDK's toolchain for arm64-v8a, for C and C++ dependencies built with cmake
# (whisper.cpp). The cmake crate recognises an NDK build by this file name;
# the NDK's own file would default to 32-bit ARM.
set(ANDROID_ABI arm64-v8a)
set(ANDROID_PLATFORM android-28)
include($ENV{ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake)
