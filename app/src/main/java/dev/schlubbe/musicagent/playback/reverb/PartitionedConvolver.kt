package dev.schlubbe.musicagent.playback.reverb

import kotlin.math.min

/**
 * Real-time-safe convolution of a mono audio stream against a (potentially
 * multi-second) impulse response, via uniform-partitioned overlap-save FFT
 * convolution - the standard architecture real convolution-reverb engines use,
 * since direct time-domain convolution against a whole multi-second IR is nowhere
 * near real-time feasible (tens of thousands of multiply-adds per output *sample*).
 *
 * The impulse response is split into [DEFAULT_BLOCK_SIZE]-sized partitions, each
 * FFT'd once up front. Processing one block of live input is then, per partition:
 * one complex multiply of that partition's (precomputed) spectrum against the
 * live signal's own (already-computed-this-block) spectrum, accumulated across all
 * partitions before a single inverse FFT - a "frequency-domain delay line" indexed
 * with a rotating [writeIndex] instead of physically shifting the whole delay line
 * every block, which would otherwise be by far the most expensive part of this.
 *
 * [process] must always be called with exactly [blockSize] samples - the caller
 * (a [ConvolutionReverbAudioProcessor]) is what buffers an audio pipeline's
 * arbitrarily-sized input/output into that fixed block size.
 */
class PartitionedConvolver(impulseResponse: FloatArray, val blockSize: Int = DEFAULT_BLOCK_SIZE) {
    private val fftSize = blockSize * 2
    private val numPartitions = (impulseResponse.size + blockSize - 1) / blockSize

    // Each impulse-response partition's precomputed spectrum - never mutated after init.
    private val irReal = Array(numPartitions) { FloatArray(fftSize) }
    private val irImag = Array(numPartitions) { FloatArray(fftSize) }

    // Frequency-domain delay line: fdlReal/Imag[(writeIndex - p) mod numPartitions]
    // holds the spectrum of the input block from `p` blocks ago.
    private val fdlReal = Array(numPartitions) { FloatArray(fftSize) }
    private val fdlImag = Array(numPartitions) { FloatArray(fftSize) }
    private var writeIndex = 0

    // Overlap-save history: the previous block's raw samples.
    private val prevInput = FloatArray(blockSize)

    // Scratch reused every call - nothing here allocates per block.
    private val scratchReal = FloatArray(fftSize)
    private val scratchImag = FloatArray(fftSize)
    private val accReal = FloatArray(fftSize)
    private val accImag = FloatArray(fftSize)

    init {
        for (p in 0 until numPartitions) {
            val start = p * blockSize
            val len = min(blockSize, impulseResponse.size - start)
            for (i in 0 until len) irReal[p][i] = impulseResponse[start + i]
            Fft.transform(irReal[p], irImag[p], inverse = false)
        }
    }

    /** Writes [blockSize] convolved samples into [output] for this [input] block -
     * both must be at least [blockSize] long; only the first [blockSize] entries of
     * each are read/written. */
    fun process(input: FloatArray, output: FloatArray) {
        // Overlap-save window: [previous block | this block], then straight to the
        // frequency domain.
        for (i in 0 until blockSize) {
            scratchReal[i] = prevInput[i]
            scratchReal[blockSize + i] = input[i]
            scratchImag[i] = 0f
            scratchImag[blockSize + i] = 0f
        }
        Fft.transform(scratchReal, scratchImag, inverse = false)
        System.arraycopy(scratchReal, 0, fdlReal[writeIndex], 0, fftSize)
        System.arraycopy(scratchImag, 0, fdlImag[writeIndex], 0, fftSize)

        java.util.Arrays.fill(accReal, 0f)
        java.util.Arrays.fill(accImag, 0f)
        for (p in 0 until numPartitions) {
            val idx = (writeIndex - p + numPartitions) % numPartitions
            val fr = fdlReal[idx]
            val fi = fdlImag[idx]
            val hr = irReal[p]
            val hi = irImag[p]
            for (k in 0 until fftSize) {
                // Complex multiply-accumulate: acc += fdl[delay p] * ir[partition p].
                accReal[k] += fr[k] * hr[k] - fi[k] * hi[k]
                accImag[k] += fr[k] * hi[k] + fi[k] * hr[k]
            }
        }

        Fft.transform(accReal, accImag, inverse = true)
        // Overlap-save: the first blockSize samples of a circular convolution are
        // wrap-around artifacts, not part of the true linear convolution - only the
        // back half is valid output.
        System.arraycopy(accReal, blockSize, output, 0, blockSize)

        System.arraycopy(input, 0, prevInput, 0, blockSize)
        writeIndex = (writeIndex + 1) % numPartitions
    }

    /** Clears all history (the frequency-domain delay line and the overlap-save
     * carry) without re-running the (real, if sub-100ms) impulse-response setup -
     * called on a seek/flush so a fade's tail doesn't leak across an unrelated jump
     * in the track. */
    fun reset() {
        java.util.Arrays.fill(prevInput, 0f)
        for (p in 0 until numPartitions) {
            java.util.Arrays.fill(fdlReal[p], 0f)
            java.util.Arrays.fill(fdlImag[p], 0f)
        }
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 1024
    }
}
