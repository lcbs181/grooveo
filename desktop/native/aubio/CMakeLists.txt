# Builds libgrooveo_aubio: aubio's analysis core (no I/O, no FFTW/BLAS, its own
# Ooura FFT) plus grooveo_aubio.c, as one shared library loaded through JNA.
# AUBIO_SRC points at an unpacked aubio release; the :desktop:buildAubio Gradle
# task downloads and verifies it and then runs this project.
cmake_minimum_required(VERSION 3.16)
project(grooveo_aubio C)

if(NOT AUBIO_SRC)
  message(FATAL_ERROR "Set -DAUBIO_SRC=<path to unpacked aubio source>")
endif()

file(GLOB_RECURSE AUBIO_SOURCES "${AUBIO_SRC}/src/*.c")
# Leave out file/audio-device I/O (Grooveo decodes with FFmpeg), the synth and
# notes modules that need it, and the FFT/DCT back ends for other libraries.
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "/src/(io|synth|notes)/")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "_(accelerate|fftw|ipp)\\.c$")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX "/windll\\.c$")

add_library(grooveo_aubio SHARED grooveo_aubio.c ${AUBIO_SOURCES})
target_include_directories(grooveo_aubio PRIVATE "${AUBIO_SRC}/src")
target_compile_definitions(grooveo_aubio PRIVATE
  HAVE_STDLIB_H=1 HAVE_STDIO_H=1 HAVE_MATH_H=1 HAVE_STRING_H=1
  HAVE_ERRNO_H=1 HAVE_LIMITS_H=1 HAVE_STDARG_H=1 HAVE_C99_VARARGS_MACROS=1)
set_target_properties(grooveo_aubio PROPERTIES C_STANDARD 99 C_VISIBILITY_PRESET hidden)
if(MSVC)
  target_compile_definitions(grooveo_aubio PRIVATE _CRT_SECURE_NO_WARNINGS _USE_MATH_DEFINES)
  # one self-contained DLL, no VC++ runtime install needed
  set_property(TARGET grooveo_aubio PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
else()
  target_link_libraries(grooveo_aubio PRIVATE m)
endif()
