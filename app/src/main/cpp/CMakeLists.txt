cmake_minimum_required(VERSION 3.22.1)
project("sendspin_audio")

find_library(log-lib log)

# Oboe: use NDK sources (reliable across all NDK versions)
# In NDK r21+, Oboe is at sources/third_party/oboe
if(EXISTS "${ANDROID_NDK}/sources/third_party/oboe/CMakeLists.txt")
    add_subdirectory("${ANDROID_NDK}/sources/third_party/oboe" "${CMAKE_CURRENT_BINARY_DIR}/oboe")
    set(OBOE_LIB oboe)
else()
    # Fallback: try prebuilt
    find_library(OBOE_LIB oboe REQUIRED)
endif()

add_library(sendspin_audio SHARED
    audio_engine.cpp
    flac_decoder.cpp
)

target_include_directories(sendspin_audio PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(sendspin_audio
    ${log-lib}
    ${OBOE_LIB}
    android
)

# Uncomment when libFLAC is cross-compiled:
# if(EXISTS ${FLAC_LIB})
#     target_link_libraries(sendspin_audio ${FLAC_LIB})
#     target_compile_definitions(sendspin_audio PRIVATE FLAC_ENABLED=1)
# endif()
