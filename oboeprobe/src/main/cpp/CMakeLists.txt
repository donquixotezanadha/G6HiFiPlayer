cmake_minimum_required(VERSION 3.22.1)
project(g6_oboe_probe LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(oboe_probe SHARED oboe_probe.cpp)
target_compile_features(oboe_probe PRIVATE cxx_std_17)
target_compile_options(oboe_probe PRIVATE -Wall -Wextra)
target_link_libraries(oboe_probe oboe::oboe log)
