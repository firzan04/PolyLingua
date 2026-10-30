cmake_minimum_required(VERSION 3.22.1)
project(polylingua_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PL_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(PL_JNILIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# ---------------------------------------------------------------------------------------------
# Fetched dependencies (scripts/fetch_native_deps.sh | .ps1):
#   third_party/sherpa-onnx/c-api.h            sherpa-onnx v1.13.8 C API header
#   third_party/onnxruntime/onnxruntime_c_api.h ONNX Runtime C API header (API v22)
#   third_party/llama.cpp/                      llama.cpp tag b12000 sources
#   ../jniLibs/arm64-v8a/libsherpa-onnx-c-api.so + libonnxruntime.so (prebuilt, packaged by AGP)
# ---------------------------------------------------------------------------------------------
foreach(required
        ${PL_THIRD_PARTY}/sherpa-onnx/c-api.h
        ${PL_THIRD_PARTY}/onnxruntime/onnxruntime_c_api.h
        ${PL_THIRD_PARTY}/llama.cpp/CMakeLists.txt
        ${PL_JNILIBS}/libsherpa-onnx-c-api.so
        ${PL_JNILIBS}/libonnxruntime.so)
    if(NOT EXISTS ${required})
        message(FATAL_ERROR "Missing ${required}\nRun scripts/fetch_native_deps.sh (Linux/macOS) or "
                "scripts\\fetch_native_deps.ps1 (Windows) from the repository root first.")
    endif()
endforeach()

# MediaTek Helio G96: Cortex-A76 (big) and Cortex-A55 (LITTLE) are both ARMv8.2-A with the
# dot-product and FP16 extensions, so this baseline is safe on every core the scheduler may use.
set(PL_ARCH_FLAGS -march=armv8.2-a+dotprod+fp16 -mtune=cortex-a76)

# ------------------------------------------------------------------------------ llama.cpp / ggml
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)            # cross-compiling: never probe the build host
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)            # ggml thread pool inherits our CPU affinity
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)         # Q4_0 -> Q4_0x4 dot-product repacking
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_SYSTEM_ARCH "ARM")
add_subdirectory(${PL_THIRD_PARTY}/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# --------------------------------------------------------------- prebuilt sherpa-onnx + ONNX Runtime
add_library(pl_sherpa_onnx_c_api SHARED IMPORTED)
set_target_properties(pl_sherpa_onnx_c_api PROPERTIES IMPORTED_LOCATION ${PL_JNILIBS}/libsherpa-onnx-c-api.so)
add_library(pl_onnxruntime SHARED IMPORTED)
set_target_properties(pl_onnxruntime PROPERTIES IMPORTED_LOCATION ${PL_JNILIBS}/libonnxruntime.so)

# ------------------------------------------------------------------------------------- Oboe
find_package(oboe REQUIRED CONFIG)

# ------------------------------------------------------------------------- polylingua_native
add_library(polylingua_native SHARED
        cpu_affinity_manager.cpp
        sherpa_bridge_jni.cpp
        gop_phoneme_engine_jni.cpp
        llama_evaluator_jni.cpp
        oboe_sidetone_engine.cpp)

target_include_directories(polylingua_native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PL_THIRD_PARTY}/sherpa-onnx
        ${PL_THIRD_PARTY}/onnxruntime
        ${PL_THIRD_PARTY}/llama.cpp/include
        ${PL_THIRD_PARTY}/llama.cpp/ggml/include)

target_compile_options(polylingua_native PRIVATE
        ${PL_ARCH_FLAGS} -O3 -fvisibility=hidden -ffunction-sections -fdata-sections
        -Wall -Wno-unused-parameter)

target_link_libraries(polylingua_native
        llama
        pl_sherpa_onnx_c_api
        pl_onnxruntime
        oboe::oboe
        android
        log)

target_link_options(polylingua_native PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
