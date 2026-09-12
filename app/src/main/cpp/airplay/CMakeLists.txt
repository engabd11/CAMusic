# airplay/CMakeLists.txt — AirPlay 2 sender (vendored from airplay2-sender-cpp)
#
# Builds the Qt-free, sans-I/O RAOP sender + its crypto core as a static
# library, plus the JNI bridge (airplay_jni.cpp) as a shared library that
# the Kotlin side loads via System.loadLibrary("airplay_sendpin").
#
# Mbed TLS 3.6 is fetched at configure time via FetchContent. This requires
# network access on the first build; subsequent builds use the CMake cache.
# The ed25519 library is vendored (no fetch needed).
#
# Apache-2.0 for the airplay2-sender-cpp code. See LICENSE and
# licenses/THIRD-PARTY-NOTICES.txt in this directory.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)

# --- Mbed TLS 3.6 (Apache-2.0): bignum, sha512, hkdf, chachapoly, drbg ---
# Fatal warnings off: a bare cmake --build must not fail on a dependency's
# -Werror when a newer compiler learns a new warning.
set(ENABLE_PROGRAMS          OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING           OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON  CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG        2ca6c285a0dd3f33982dd57299012dacab1ff206   # v3.6.0, pinned by commit
    GIT_SHALLOW    TRUE)
FetchContent_MakeAvailable(mbedtls)

# --- orlp/ed25519 (zlib, vendored): the one primitive Mbed TLS lacks ---
# Drop seed.c and define ED25519_NO_SEED: ed25519_create_seed() is never
# called (all seeds come from Mbed TLS CTR-DRBG).
file(GLOB ED25519_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ed25519/src/*.c)
list(FILTER ED25519_SRC EXCLUDE REGEX "/seed\\.c$")
add_library(ed25519 STATIC ${ED25519_SRC})
target_compile_definitions(ed25519 PRIVATE ED25519_NO_SEED)
target_include_directories(ed25519 PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ed25519/src)

# --- The Qt-free AirPlay 2 crypto + wire-format core (the reusable part) ---
add_library(airplay_crypto STATIC src/airplay_crypto.cpp)
target_include_directories(airplay_crypto PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ed25519/src)
target_link_libraries(airplay_crypto PUBLIC mbedcrypto ed25519)
target_compile_features(airplay_crypto PUBLIC cxx_std_20)

# --- The sender (sans-I/O state machine) + the bundled poll host ---
# raop_sender knows no socket; RaopLoop is the portable host that drives it.
# We exclude raop_qt_host.cpp (no Qt on Android).
add_library(raop_sender STATIC
    src/raop_sender.cpp
    src/raop_loop.cpp)
target_include_directories(raop_sender PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(raop_sender PUBLIC airplay_crypto)
target_compile_features(raop_sender PUBLIC cxx_std_20)

# --- The JNI bridge: implements RaopIo via POSIX sockets and exposes
# the sender to Kotlin through JNI. ---
add_library(airplay_sendpin SHARED
    airplay_jni.cpp)
target_include_directories(airplay_sendpin PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(airplay_sendpin
    raop_sender
    ${log-lib}
    android)

# 16 KB page sizes (matches the existing sendspin_output target).
target_link_options(airplay_sendpin PRIVATE "-Wl,-z,max-page-size=16384")