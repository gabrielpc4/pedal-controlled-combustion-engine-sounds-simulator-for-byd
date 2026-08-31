cmake_minimum_required(VERSION 3.24)
project(fh6_audio_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FH6_FMOD_SDK "" CACHE PATH "FMOD Studio API directory matching the installed FH6 major/product version")
if(NOT FH6_FMOD_SDK)
  message(FATAL_ERROR "Set FH6_FMOD_SDK to a licensed local FMOD Studio API SDK. No FMOD binary is redistributed.")
endif()

add_library(fh6_audio_bridge SHARED src/fh6_audio_bridge.cpp)
target_include_directories(fh6_audio_bridge PRIVATE "${FH6_FMOD_SDK}/api/core/inc" "${FH6_FMOD_SDK}/api/studio/inc")
target_link_directories(fh6_audio_bridge PRIVATE "${FH6_FMOD_SDK}/api/core/lib/x64" "${FH6_FMOD_SDK}/api/studio/lib/x64")
target_link_libraries(fh6_audio_bridge PRIVATE fmod_vc fmodstudio_vc)
target_compile_definitions(fh6_audio_bridge PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
