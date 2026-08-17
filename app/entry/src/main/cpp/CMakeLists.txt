cmake_minimum_required(VERSION 3.22.1)

project(mytools_native_modules CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mobi_reader SHARED mobi_reader_napi.cpp)
target_link_libraries(mobi_reader PRIVATE libace_napi.z.so)
