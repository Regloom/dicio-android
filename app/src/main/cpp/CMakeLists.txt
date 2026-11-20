cmake_minimum_required(VERSION 3.22)
project(dicio_whisper C CXX)

# whisper.cpp is unusably slow without optimizations, so always build it in Release mode, even for
# debug builds of the app
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# build whisper.cpp and ggml as static libraries that get linked into libdicio_whisper.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # fp16 and dotprod are available on practically all arm64 phones still in use (ARMv8.2+)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif ()

add_subdirectory(whisper.cpp)

add_library(dicio_whisper SHARED whisper_jni.cpp)
target_link_libraries(dicio_whisper PRIVATE whisper log)
target_compile_options(dicio_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(dicio_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
