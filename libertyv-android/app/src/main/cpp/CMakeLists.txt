cmake_minimum_required(VERSION 3.22.1)
project(awcdec C)

if(NOT FFMPEG_ROOT)
    message(FATAL_ERROR "Falta FFMPEG_ROOT (ejecuta scripts/build-ffmpeg.sh)")
endif()
set(FF ${FFMPEG_ROOT}/${ANDROID_ABI})
if(NOT EXISTS ${FF}/lib/libavcodec.a)
    message(FATAL_ERROR "No se encontró FFmpeg en ${FF}. Ejecuta scripts/build-ffmpeg.sh")
endif()

add_library(awcdec SHARED awcdec_core.c awcdec_jni.c)
target_include_directories(awcdec PRIVATE ${FF}/include)
target_link_libraries(awcdec ${FF}/lib/libavcodec.a ${FF}/lib/libavutil.a m log)
# Páginas de 16 KB (Android 15+).
target_link_options(awcdec PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
