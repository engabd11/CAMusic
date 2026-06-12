cmake_minimum_required(VERSION 3.22.1)
project("sendspin_audio")

find_library(log-lib log)
find_library(oboe-lib oboe)

# Optional: use prebuilt libFLAC if available
# set(FLAC_LIB ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libFLAC.a)

add_library(sendspin_audio SHARED
    audio_engine.cpp
    flac_decoder.cpp
)

target_include_directories(sendspin_audio PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(sendspin_audio
    ${log-lib}
    ${oboe-lib}
    android
)

# Uncomment when libFLAC is cross-compiled:
# if(EXISTS ${FLAC_LIB})
#     target_link_libraries(sendspin_audio ${FLAC_LIB})
#     target_compile_definitions(sendspin_audio PRIVATE FLAC_ENABLED=1)
# endif()
