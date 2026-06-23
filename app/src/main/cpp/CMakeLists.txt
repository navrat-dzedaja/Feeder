# Native build for the on-device llama.cpp summarizer (FOSS, both flavors).
#
# llama.cpp is vendored as a git submodule at ./llama.cpp. The JNI bridge (ai_chat.cpp) and the
# Kotlin engine come from llama.cpp's own official Android example, so they stay version-matched
# with the submodule. Produces libai-chat.so.

cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

# JNI bridge (ai_chat.cpp) + logging.h are vendored from the submodule's official Android example
# into this dir, with logging.h patched to keep the app's minSdk 29 (see the file for details).
add_library(${CMAKE_PROJECT_NAME} SHARED
        ${CMAKE_CURRENT_LIST_DIR}/ai_chat.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
