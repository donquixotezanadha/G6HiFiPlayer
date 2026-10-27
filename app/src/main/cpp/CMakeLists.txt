cmake_minimum_required(VERSION 3.18.1)
project(g6direct LANGUAGES CXX)

add_library(g6direct SHARED g6direct.cpp)
set_target_properties(g6direct PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
find_library(log-lib log)
target_link_libraries(g6direct ${log-lib} dl)
