cmake_minimum_required(VERSION 3.22.1)

project(atlas_pcm LANGUAGES CXX)

add_library(atlas_pcm SHARED atlas_pcm.cpp)

target_compile_features(atlas_pcm PRIVATE cxx_std_17)
target_compile_options(atlas_pcm PRIVATE -Wall -Wextra -Werror)
