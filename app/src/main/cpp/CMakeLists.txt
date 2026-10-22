cmake_minimum_required(VERSION 3.22.1)
project(g6aaudio)

add_library(g6aaudio SHARED aaudio_output.cpp)
find_library(aaudio-lib aaudio)
find_library(log-lib log)
find_library(android-lib android)

target_compile_features(g6aaudio PRIVATE cxx_std_17)
target_compile_options(g6aaudio PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(g6aaudio PRIVATE ${aaudio-lib} ${android-lib} ${log-lib})
