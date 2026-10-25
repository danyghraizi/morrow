cmake_minimum_required(VERSION 3.22.1)
project(morrow LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is pulled at configure time and pinned by tag + checksum, so the
# native build is reproducible without vendoring ~40 MB of third-party source.
include(FetchContent)

set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE        OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        llama
        URL      https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11879.tar.gz
        URL_HASH SHA256=136b56a79984509bed2100430a227e2ca97c6a6bb44f194487ae997c28df9380
        DOWNLOAD_EXTRACT_TIMESTAMP ON
)
FetchContent_MakeAvailable(llama)

add_library(morrow SHARED llm_bridge.cpp)
# Only our bridge is exception-free; llama.cpp itself uses exceptions.
target_compile_options(morrow PRIVATE -fno-exceptions -fno-rtti)
target_link_libraries(morrow PRIVATE llama ggml log)
