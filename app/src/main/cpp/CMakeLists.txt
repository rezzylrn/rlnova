# Rlnova native — libllama (prebuilt M1) + JNI bridge
cmake_minimum_required(VERSION 3.14)
project(rlnova-bridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
get_filename_component(PROJ_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../ ABSOLUTE)

add_library(rlnova-bridge SHARED bridge.cpp)

target_include_directories(rlnova-bridge PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include)

# Prebuilt dari M1/vulkan — CMAKE_LIBRARY_OUTPUT_DIRECTORY dikosongkan; path
# absolut langsung ke build-android/bin (hasil build libllama.so dgn backend
# Vulkan, v1.1.5; DT_NEEDED libggml-vulkan).
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION
        ${PROJ_ROOT}/build-android/bin/libllama.so)

target_link_libraries(rlnova-bridge
        llama
        android
        log)
