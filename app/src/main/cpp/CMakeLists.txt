cmake_minimum_required(VERSION 3.22.1)
project(steamoslite C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# adrenotools: loads the bundled Turnip driver for the compositor. Its hook libraries
# (libmain_hook.so etc.) are built as shared libraries and packaged beside it.
add_subdirectory(adrenotools)
add_subdirectory(waylandcomp)

# Small JNI helpers for the app itself (the store fence the controller rings publish with).
add_library(slnative SHARED slnative.c)
target_link_libraries(slnative log)
