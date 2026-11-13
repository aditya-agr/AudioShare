package com.audioshare

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTest {
    @Test fun offsetFromRoundTrip() {
        // Host is 1000 ns ahead; 200 ns each way. Sent at local 0, host stamps 1200, back at local 400.
        assertEquals(1000L, clockOffset(t0 = 0, hostTime = 1200, t3 = 400))
    }

    @Test fun dropOrPad() {
        assertEquals(0, correction(5_000_000))       // 5 ms off: fine
        assertEquals(-1, correction(20_000_000))     // 20 ms late: drop packet
        assertEquals(4800, correction(-100_000_000)) // 100 ms early: 100 ms of silence
    }

    private fun resample(r: Resampler, input: ShortArray, channels: Int, rate: Int) =
        mutableListOf<Pair<Short, Short>>().also { out -> r.process(input, channels, rate) { a, b -> out += a to b } }

    @Test fun resamplerPassthroughIsContinuousAcrossChunks() {
        val r = Resampler()
        val first = resample(r, shortArrayOf(1, 2, 3), channels = 1, rate = 48000)
        val second = resample(r, shortArrayOf(4, 5, 6), channels = 1, rate = 48000)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), (first + second).map { it.first })
        assertEquals((first + second).map { it.first }, (first + second).map { it.second }) // mono -> both sides
    }

    @Test fun resamplerUpsamplesByInterpolating() {
        val out = resample(Resampler(), shortArrayOf(0, 0, 100, -100), channels = 2, rate = 24000)
        assertEquals(listOf<Pair<Short, Short>>(0.toShort() to 0.toShort(), 50.toShort() to (-50).toShort()), out)
    }
}
