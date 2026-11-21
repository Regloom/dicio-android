cmake_minimum_required(VERSION 3.22)
project(dicio_whisper C CXX)

# whisper.cpp is unusably slow without optimizations, so always build it in Release mode, even for
# debug builds of the app
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# whisper.cpp and ggml are shared libraries, and the ggml CPU backend is built in several variants
# for different ARM feature levels (armv8.0, 8.2+dotprod, 8.2+fp16, 8.6+i8mm, 9.0+SVE2, 9.2+SME).
# At runtime ggml scores the CPU and loads the best variant (see loadBackends() in whisper_jni.cpp),
# so new phones get the fast kernels and old ones still work instead of crashing with SIGILL.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(dicio_whisper SHARED whisper_jni.cpp)
target_link_libraries(dicio_whisper PRIVATE whisper ggml log)
target_compile_options(dicio_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(dicio_whisper PRIVATE -Wl,--gc-sections)
