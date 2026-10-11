# Builds libgrooveo_native, loaded through JNA:
#  - aubio's analysis core (beat tracking; no I/O, no FFTW/BLAS, its own Ooura FFT)
#    plus grooveo_aubio.c
#  - Signalsmith Stretch (header-only C++, time-stretching) plus grooveo_stretch.cpp
# AUBIO_SRC, STRETCH_SRC and LINEAR_SRC point at unpacked releases; the
# :desktop:buildNative Gradle task downloads and verifies them and runs this project.
cmake_minimum_required(VERSION 3.16)
project(grooveo_native C CXX)

foreach(dir AUBIO_SRC STRETCH_SRC LINEAR_SRC)
  if(NOT ${dir})
    message(FATAL_ERROR "Set -D${dir}=<path to unpacked source>")
  endif()
endforeach()

file(GLOB_RECURSE AUBIO_SOURCES "${AUBIO_SRC}/src/*.c")
# Leave out file/audio-device I/O (Grooveo decodes with FFmpeg), the synth and
# notes modules that need it, and the FFT/DCT back ends for other libraries.
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "/src/(io|synth|notes)/")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "_(accelerate|fftw|ipp)\\.c$")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "/windll\\.c$")

add_library(grooveo_native SHARED grooveo_aubio.c grooveo_stretch.cpp ${AUBIO_SOURCES})
target_include_directories(grooveo_native PRIVATE "${AUBIO_SRC}/src" "${STRETCH_SRC}/include" "${LINEAR_SRC}/include")
target_compile_definitions(grooveo_native PRIVATE
  HAVE_STDLIB_H=1 HAVE_STDIO_H=1 HAVE_MATH_H=1 HAVE_STRING_H=1
  HAVE_ERRNO_H=1 HAVE_LIMITS_H=1 HAVE_STDARG_H=1 HAVE_C99_VARARGS_MACROS=1)
set_target_properties(grooveo_native PROPERTIES
  C_STANDARD 99 CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON
  C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
if(MSVC)
  target_compile_definitions(grooveo_native PRIVATE _CRT_SECURE_NO_WARNINGS _USE_MATH_DEFINES)
  # one self-contained DLL, no VC++ runtime install needed
  set_property(TARGET grooveo_native PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
else()
  target_link_libraries(grooveo_native PRIVATE m)
  if(NOT APPLE)
    # no libstdc++/libgcc dependency on the user's system
    target_link_options(grooveo_native PRIVATE -static-libstdc++ -static-libgcc)
  endif()
endif()
