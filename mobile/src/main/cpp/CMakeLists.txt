cmake_minimum_required(VERSION 3.22.1)

project(byd_fmod_bridge LANGUAGES CXX)

if(NOT DEFINED FMOD_STAGED_DIR OR FMOD_STAGED_DIR STREQUAL "")
    message(FATAL_ERROR
        "FMOD_STAGED_DIR is missing. Set fmod.sdk.dir to the official FMOD Studio API "
        "1.10.11 Android SDK in local.properties and run prepareFmodSdk.")
endif()

set(FMOD_CORE_INCLUDE "${FMOD_STAGED_DIR}/include/core")
set(FMOD_STUDIO_INCLUDE "${FMOD_STAGED_DIR}/include/studio")
set(FMOD_CORE_LIBRARY "${FMOD_STAGED_DIR}/jniLibs/${ANDROID_ABI}/libfmod.so")
set(FMOD_STUDIO_LIBRARY "${FMOD_STAGED_DIR}/jniLibs/${ANDROID_ABI}/libfmodstudio.so")

foreach(REQUIRED_PATH
        "${FMOD_CORE_INCLUDE}/fmod.hpp"
        "${FMOD_STUDIO_INCLUDE}/fmod_studio.hpp"
        "${FMOD_CORE_LIBRARY}"
        "${FMOD_STUDIO_LIBRARY}")
    if(NOT EXISTS "${REQUIRED_PATH}")
        message(FATAL_ERROR
            "Required FMOD 1.10.11 input is missing: ${REQUIRED_PATH}. "
            "Check fmod.sdk.dir in local.properties.")
    endif()
endforeach()

add_library(byd_fmod_bridge SHARED fmod_bridge.cpp)
target_compile_features(byd_fmod_bridge PRIVATE cxx_std_17)
target_include_directories(byd_fmod_bridge PRIVATE "${FMOD_CORE_INCLUDE}" "${FMOD_STUDIO_INCLUDE}")
target_compile_definitions(byd_fmod_bridge PRIVATE FMOD_ANDROID=1)

find_library(log_library log REQUIRED)
find_library(android_library android REQUIRED)
find_library(dl_library dl REQUIRED)

target_link_libraries(
    byd_fmod_bridge
    PRIVATE
    "${android_library}"
    "${dl_library}"
    "${log_library}"
)
