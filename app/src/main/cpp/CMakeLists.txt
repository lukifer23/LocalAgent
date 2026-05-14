cmake_minimum_required(VERSION 3.22.1)

project(localagent_native)

find_library(LOG_LIB log)

add_library(localagent_native SHARED
    pty_jni.cpp
    pty_session.cpp
)

target_include_directories(localagent_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(localagent_native PRIVATE cxx_std_20)
target_link_libraries(localagent_native ${LOG_LIB})

option(LOCALAGENT_BUILD_LLAMA "Build llama.cpp-backed local inference JNI" ON)

if (LOCALAGENT_BUILD_LLAMA)
    include(FetchContent)
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(llama)

    add_library(localagent_llama SHARED llama_jni.cpp)
    target_include_directories(localagent_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include
    )
    target_link_libraries(localagent_llama ${LOG_LIB} llama)
endif()
