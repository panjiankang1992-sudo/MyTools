cmake_minimum_required(VERSION 3.22.1)

project(mytools_native_modules C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mobi_reader SHARED mobi_reader_napi.cpp)
target_link_libraries(mobi_reader PRIVATE libace_napi.z.so)

include(SevenZipSources.cmake)
add_library(sevenzip SHARED ${SEVENZIP_SOURCES})
target_compile_definitions(sevenzip PRIVATE Z7_EXTERNAL_CODECS _REENTRANT _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE)
target_compile_options(sevenzip PRIVATE -O2)
add_library(archive_engine SHARED archive_engine_core.cpp archive_engine_napi.cpp)
target_link_libraries(archive_engine PRIVATE sevenzip libace_napi.z.so)

# 二维码编解码在设备后台线程中运行，不依赖网络或 ScanKit 编码服务。
add_library(qr_engine SHARED qr_engine_core.cpp qr_engine_napi.cpp
    vendor/qr/qrcodegen/qrcodegen.c vendor/qr/quirc/quirc.c
    vendor/qr/quirc/decode.c vendor/qr/quirc/identify.c vendor/qr/quirc/version_db.c)
target_link_libraries(qr_engine PRIVATE libace_napi.z.so)

# 长内容二维码需要更多连通区域，避免密集码被默认区域上限截断。
target_compile_definitions(qr_engine PRIVATE QUIRC_MAX_REGIONS=8192)

# 使用回环监听向本应用内的原生播放器提供背压受控的传输。
add_library(home_channel SHARED home_channel_napi.cpp)
target_link_libraries(home_channel PRIVATE libace_napi.z.so libuv.so)
