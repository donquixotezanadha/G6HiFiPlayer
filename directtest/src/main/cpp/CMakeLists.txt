cmake_minimum_required(VERSION 3.22.1)
project(aaudio_probe)

add_library(aaudio_probe SHARED aaudio_probe.cpp)
target_compile_features(aaudio_probe PRIVATE cxx_std_17)
find_library(aaudio-lib aaudio)
find_library(log-lib log)
target_link_libraries(aaudio_probe ${aaudio-lib} ${log-lib})
