package com.chiller3.bcr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmGainTest {
    private fun buffer(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            for (value in values) b.putShort(value.toShort())
            b.flip()
        }

    @Test fun disabledGainLeavesSamplesIdentical() {
        val b = buffer(-32768, -1234, -1, 0, 1, 1234, 32767)
        val before = b.array().clone()
        PcmGain(0).apply(b)
        assertTrue(before.contentEquals(b.array()))
    }

    @Test fun sixDbGainBoostsQuietSamplesApproximatelyTwofold() {
        val b = buffer(-1000, 0, 1000)
        PcmGain(6).apply(b)
        assertEquals(-1995, b.getShort(0).toInt())
        assertEquals(0, b.getShort(2).toInt())
        assertEquals(1995, b.getShort(4).toInt())
    }

    @Test fun limiterPreventsOverflowAndKeepsPolarity() {
        val b = buffer(-32768, -30_000, -25_000, 0, 25_000, 30_000, 32767)
        PcmGain(9).apply(b)
        for (i in 0 until b.limit() step 2) {
            val value = b.getShort(i).toInt()
            assertTrue("Must remain within signed PCM range", value in -32767..32767)
        }
        assertTrue(b.getShort(0) < 0)
        assertTrue(b.getShort(12) > 0)
        assertTrue(b.getShort(12).toInt() <= 32767)
    }

    @Test fun gainDoesNotChangeBufferPositionOrLimit() {
        val b = buffer(1000, 2000, 3000)
        b.position(2)
        PcmGain(3).apply(b)
        assertEquals(2, b.position())
        assertEquals(6, b.limit())
    }

    @Test fun rejectsUnknownGainValues() {
        assertThrows(IllegalArgumentException::class.java) { PcmGain(-3) }
        assertThrows(IllegalArgumentException::class.java) { PcmGain(12) }
    }
}
