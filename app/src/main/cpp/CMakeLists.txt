cmake_minimum_required(VERSION 3.22)
project(openblur_mvtools LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(MV ${CMAKE_CURRENT_SOURCE_DIR}/vendor/mvtools/src)
add_library(mvtools_core STATIC
 ${MV}/CopyCode.cpp ${MV}/CPU.cpp ${MV}/Fakery.cpp ${MV}/GroupOfPlanes.cpp
 ${MV}/Luma.cpp ${MV}/Overlap.cpp ${MV}/MaskFun.cpp ${MV}/MVAnalysisData.cpp ${MV}/MVFrame.cpp
 ${MV}/PlaneOfBlocks.cpp ${MV}/SADFunctions.cpp ${MV}/SimpleResize.cpp
 dct_disabled.cpp)
target_include_directories(mvtools_core PUBLIC ${MV} vendor/vapoursynth/include compatibility)
set_target_properties(mvtools_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(ANDROID_ABI STREQUAL "arm64-v8a")
 target_compile_definitions(mvtools_core PRIVATE MVTOOLS_ARM)
endif()
target_compile_options(mvtools_core PRIVATE -include cstddef)
add_library(openblur_mvtools SHARED engine.cpp jni_bridge.cpp)
target_link_libraries(openblur_mvtools PRIVATE mvtools_core)
target_link_options(openblur_mvtools PRIVATE -Wl,-z,max-page-size=16384)
add_executable(native_smoke smoke.cpp)
target_link_libraries(native_smoke PRIVATE openblur_mvtools)
