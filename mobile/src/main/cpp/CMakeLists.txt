cmake_minimum_required(VERSION 3.22.1)
project(byd_fmod_bank_bridge)

if(NOT DEFINED FMOD_STAGED_DIR)
    set(FMOD_STAGED_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/generated/fmodSdk")
endif()

add_library(fmod SHARED IMPORTED)
set_target_properties(fmod PROPERTIES
    IMPORTED_LOCATION "${FMOD_STAGED_DIR}/lib/${ANDROID_ABI}/libfmod.so"
)

add_library(fmodstudio SHARED IMPORTED)
set_target_properties(fmodstudio PROPERTIES
    IMPORTED_LOCATION "${FMOD_STAGED_DIR}/lib/${ANDROID_ABI}/libfmodstudio.so"
)

add_library(byd_fmod_bank_bridge SHARED fmod_bank_bridge.cpp)

target_compile_features(byd_fmod_bank_bridge PRIVATE cxx_std_17)
target_compile_options(byd_fmod_bank_bridge PRIVATE -Wall -Wextra -Werror=return-type)
target_include_directories(byd_fmod_bank_bridge PRIVATE "${FMOD_STAGED_DIR}/include")
target_link_libraries(byd_fmod_bank_bridge android log fmod fmodstudio)
