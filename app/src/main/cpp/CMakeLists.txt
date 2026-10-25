cmake_minimum_required(VERSION 3.18.1)
project(g6sles LANGUAGES CXX)

add_library(g6sles SHARED g6sles.cpp)
set_target_properties(g6sles PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
find_library(log-lib log)
# libOpenSLES.so is a public NDK library, so the app's linker namespace allows it
# (unlike libaudioclient/libmedia, which Build 1.36 proved are blocked).
target_link_libraries(g6sles OpenSLES ${log-lib})
