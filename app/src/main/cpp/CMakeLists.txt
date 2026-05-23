cmake_minimum_required(VERSION 3.22)
project(stockfish_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(SF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stockfish/src)

set(SF_SOURCES
    ${SF_DIR}/benchmark.cpp
    ${SF_DIR}/bitboard.cpp
    ${SF_DIR}/engine.cpp
    ${SF_DIR}/evaluate.cpp
    ${SF_DIR}/main.cpp
    ${SF_DIR}/memory.cpp
    ${SF_DIR}/misc.cpp
    ${SF_DIR}/movegen.cpp
    ${SF_DIR}/movepick.cpp
    ${SF_DIR}/nnue/features/full_threats.cpp
    ${SF_DIR}/nnue/features/half_ka_v2_hm.cpp
    ${SF_DIR}/nnue/network.cpp
    ${SF_DIR}/nnue/nnue_accumulator.cpp
    ${SF_DIR}/nnue/nnue_misc.cpp
    ${SF_DIR}/position.cpp
    ${SF_DIR}/score.cpp
    ${SF_DIR}/search.cpp
    ${SF_DIR}/syzygy/tbprobe.cpp
    ${SF_DIR}/thread.cpp
    ${SF_DIR}/timeman.cpp
    ${SF_DIR}/tt.cpp
    ${SF_DIR}/tune.cpp
    ${SF_DIR}/uci.cpp
    ${SF_DIR}/ucioption.cpp
)

# Mirror Stockfish's Makefile for ARCH=armv8 + standard release flags.
# NNUE_EMBEDDING_OFF: skips incbin'ing the .nnue files at build time. The engine
# will start without a network; we'll load NNUE from an Android asset in Phase 2b.
add_library(stockfish_obj OBJECT ${SF_SOURCES})

target_compile_definitions(stockfish_obj PRIVATE
    NDEBUG
    IS_64BIT
    USE_PTHREADS
    USE_POPCNT
    USE_NEON=8
    NNUE_EMBEDDING_OFF
)

target_compile_options(stockfish_obj PRIVATE
    -O3
    -fno-exceptions
    -ffunction-sections
    -fdata-sections
    -Wno-deprecated-declarations
    -Wno-unused-parameter
)

target_include_directories(stockfish_obj PRIVATE
    ${SF_DIR}
    ${SF_DIR}/incbin
)

add_library(stockfish_bridge SHARED
    stockfish_bridge.cpp
    $<TARGET_OBJECTS:stockfish_obj>
)

target_compile_definitions(stockfish_bridge PRIVATE
    NDEBUG
    IS_64BIT
    USE_PTHREADS
    USE_POPCNT
    USE_NEON=8
    NNUE_EMBEDDING_OFF
)

target_compile_options(stockfish_bridge PRIVATE
    -O3
    -fno-exceptions
    -ffunction-sections
    -fdata-sections
)

target_include_directories(stockfish_bridge PRIVATE
    ${SF_DIR}
    ${SF_DIR}/incbin
)

find_library(log-lib log)
target_link_libraries(stockfish_bridge PRIVATE ${log-lib})

# Strip dead code to keep the .so reasonably sized.
target_link_options(stockfish_bridge PRIVATE
    -Wl,--gc-sections
)
