package com.chiller3.bcr.audio

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Optional digital gain for signed 16-bit PCM, before the codec.
 *
 * At low amplitudes the requested gain is linear. A continuous soft-knee
 * limiter bends peaks toward full scale instead of wrapping or hard clipping.
 * Disabled at 0 dB to preserve existing BCR output byte-for-byte.
 */
class PcmGain(gainDb: Int) {
    private val samples: ShortArray?

    init {
        require(gainDb in OPTIONS_DB) { "Unsupported gain: $gainDb dB" }
        samples = if (gainDb == 0) null else createLookup(gainDb)
    }

    /** Modifies [buffer] in-place up to limit(), preserving position and limit. */
    fun apply(buffer: ByteBuffer) {
        val lookup = samples ?: return
        require(buffer.limit() % Short.SIZE_BYTES == 0) { "PCM buffer must be sample-aligned" }
        var offset = 0
        while (offset < buffer.limit()) {
            val input = buffer.getShort(offset).toInt()
            buffer.putShort(offset, lookup[input + 32768])
            offset += Short.SIZE_BYTES
        }
    }

    companion object {
        val OPTIONS_DB = listOf(0, 3, 6, 9)
        private const val KNEE = 24_000.0
        private const val CEILING = 32_767.0

        private fun createLookup(db: Int): ShortArray {
            val factor = 10.0.pow(db / 20.0)
            return ShortArray(65_536) { index ->
                val signed = index - 32768
                if (signed == 0) {
                    0
                } else {
                    val boosted = abs(signed.toDouble()) * factor
                    val limited = if (boosted <= KNEE) {
                        boosted
                    } else {
                        val headroom = CEILING - KNEE
                        KNEE + headroom * (1.0 - exp(-(boosted - KNEE) / headroom))
                    }
                    val magnitude = limited.roundToInt().coerceIn(0, 32767)
                    (if (signed < 0) -magnitude else magnitude).toShort()
                }
            }
        }
    }
}
