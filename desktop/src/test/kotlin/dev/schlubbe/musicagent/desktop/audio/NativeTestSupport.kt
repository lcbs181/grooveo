package dev.schlubbe.musicagent.desktop.audio

import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.assertTrue

/**
 * For tests that need libgrooveo_native: skipped when Gradle could not build it (no
 * CMake, no download), otherwise it must load.
 */
fun requireNative() {
    assumeFalse(System.getProperty("grooveo.native.skipped") == "true", "native DSP library not built")
    assertTrue(TimeStretcher.available, "native library must be built and loadable (grooveo.native.dir)")
}
