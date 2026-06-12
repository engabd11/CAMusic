cmake_minimum_required(VERSION 3.22.1)
project("sendspin_audio")

find_library(log-lib log)

# Oboe: use NDK source (most reliable across NDK versions)
# NDK r26+ provides Oboe at sources/third_party/oboe
set(OBOE_DIR "${CMAKE_ANDROID_NDK}/sources/third_party/oboe")
if(EXISTS "${OBOE_DIR}/CMakeLists.txt")
    add_subdirectory("${OBOE_DIR}" oboe_build)
    set(OBOE_TARGET oboe)
else()
    # Fallback: try prebuilt find_library
    find_library(OBOE_TARGET oboe)
endif()

add_library(sendspin_audio SHARED
    audio_engine.cpp
    flac_decoder.cpp
)

target_include_directories(sendspin_audio PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(sendspin_audio
    ${log-lib}
    ${OBOE_TARGET}
    android
)

# Uncomment when libFLAC is cross-compiled:
# if(EXISTS ${FLAC_LIB})
#     target_link_libraries(sendspin_audio ${FLAC_LIB})
#     target_compile_definitions(sendspin_audio PRIVATE FLAC_ENABLED=1)
# endif()
