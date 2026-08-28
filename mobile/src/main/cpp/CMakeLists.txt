cmake_minimum_required(VERSION 3.22.1)
project(engine_audio_native LANGUAGES C CXX)

include(FetchContent)

# FLAC is fetched into CMake's ignored build tree, not copied into the application sources.
# Pin both the release and upstream checksum so a future remote change cannot alter the APK.
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    flac
    URL https://downloads.xiph.org/releases/flac/flac-1.5.0.tar.xz
    URL_HASH SHA256=f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(flac)

# Android API 24 exposes 64-bit fseeko/ftello on 32-bit ARM, but libFLAC's
# cross-compile link probe reports a false negative for that ABI. Defining this only
# for armv7 avoids its incompatible fseek/ftell fallback without altering other ABIs.
if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(FLAC PRIVATE HAVE_FSEEKO=1)
endif()

add_library(engine_audio_native SHARED native_flac.cpp)
target_compile_features(engine_audio_native PRIVATE cxx_std_20)
target_compile_options(engine_audio_native PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -ffast-math
)
target_link_libraries(engine_audio_native PRIVATE FLAC)
