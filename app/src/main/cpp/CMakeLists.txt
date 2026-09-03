cmake_minimum_required(VERSION 3.22.1)
project("sendspin_output")

find_library(log-lib log)

# Oboe via the com.google.oboe:oboe prefab Maven package (see build.gradle.kts) -
# NDK-bundled sources at sources/third_party/oboe were removed from newer NDKs.
find_package(oboe REQUIRED CONFIG)

add_library(sendspin_output SHARED
    sendspin_output_engine.cpp
    sendspin_output_jni.cpp
)

add_library(sendspin_aaudio SHARED
    sendspin_aaudio.cpp
)

# AAudio is NDK-bundled, unlike Oboe, so no prefab dependency.
target_link_libraries(sendspin_aaudio
    ${log-lib}
    aaudio
    android
)

target_include_directories(sendspin_output PRIVATE ${CMAKE_SOURCE_DIR})
# Oboe's public headers require C++17.
target_compile_features(sendspin_output PRIVATE cxx_std_17)

target_link_libraries(sendspin_output
    ${log-lib}
    oboe::oboe
    android
)

# 16 KB page sizes apply to every native library built here.
target_link_options(sendspin_aaudio PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(sendspin_output PRIVATE "-Wl,-z,max-page-size=16384")