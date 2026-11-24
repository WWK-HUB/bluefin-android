package com.wwkhub.bluefin.voice.audio

import com.wwkhub.bluefin.voice.tts.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A written clip must read back through the parser the engines actually use. */
class WavWriterTest {

    @Test
    fun writtenClipRoundTripsThroughWavReader() {
        val samples = FloatArray(4800) { index -> (index % 200 - 100) / 200f }
        val bytes = ByteArrayOutputStream().also { sink ->
            WavWriter.write(sink, samples, 24_000)
        }.toByteArray()

        val parsed = WavReader.parse(bytes)
        assertNotNull(parsed)
        assertEquals(24_000, parsed!!.sampleRate)
        assertEquals(samples.size, parsed.samples.size)
        parsed.samples.forEachIndexed { index, value ->
            assertEquals(samples[index], value, 1e-4f)
        }
    }

    @Test
    fun headerDeclaresSixteenBitMono() {
        val bytes = ByteArrayOutputStream().also { sink ->
            WavWriter.write(sink, FloatArray(10), 16_000)
        }.toByteArray()

        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, leInt(bytes, 16))
        assertEquals(1, leShort(bytes, 20))
        assertEquals(1, leShort(bytes, 22))
        assertEquals(16_000, leInt(bytes, 24))
        assertEquals(16_000 * 2, leInt(bytes, 28))
        assertEquals(2, leShort(bytes, 32))
        assertEquals(16, leShort(bytes, 34))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(20, leInt(bytes, 40))
        assertEquals(44 + 20, bytes.size)
        assertEquals(bytes.size - 8, leInt(bytes, 4))
    }

    @Test
    fun outOfRangeSamplesAreClampedNotWrapped() {
        val bytes = ByteArrayOutputStream().also { sink ->
            WavWriter.write(sink, floatArrayOf(2f, -2f, 1f, -1f), 24_000)
        }.toByteArray()
        val parsed = WavReader.parse(bytes)!!

        assertEquals(4, parsed.samples.size)
        assertTrue("loudest positive sample must clamp to the same ceiling", parsed.samples[0] > 0.99f)
        assertTrue("loudest negative sample must clamp to the same floor", parsed.samples[1] < -0.99f)
        assertEquals(parsed.samples[0], parsed.samples[2], 1e-6f)
        assertEquals(parsed.samples[1], parsed.samples[3], 1e-6f)
    }

    @Test
    fun emptyClipStillWritesAValidHeader() {
        val bytes = ByteArrayOutputStream().also { sink ->
            WavWriter.write(sink, FloatArray(0), 24_000)
        }.toByteArray()

        assertEquals(44, bytes.size)
        assertEquals(0, leInt(bytes, 40))
    }

    @Test
    fun nanBecomesSilenceInsteadOfGarbage() {
        val bytes = ByteArrayOutputStream().also { sink ->
            WavWriter.write(sink, floatArrayOf(Float.NaN), 24_000)
        }.toByteArray()

        assertEquals(0f, WavReader.parse(bytes)!!.samples[0], 0f)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
