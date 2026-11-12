package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPetPolicyTest {
    @Test
    fun scaleStaysInsideTheApprovedBand() {
        assertEquals(0.50f, OverlayPetPolicy.scaleFor(0.50f))
        assertEquals(0.70f, OverlayPetPolicy.scaleFor(0.70f))
        assertEquals(0.85f, OverlayPetPolicy.scaleFor(0.85f))
        assertEquals(1.00f, OverlayPetPolicy.scaleFor(1.00f))
        assertEquals(1.20f, OverlayPetPolicy.scaleFor(1.20f))
        assertEquals(1.30f, OverlayPetPolicy.scaleFor(1.30f))
        assertEquals(0.50f, OverlayPetPolicy.scaleFor(0.20f))
        assertEquals(1.30f, OverlayPetPolicy.scaleFor(1.50f))
        assertEquals(0.85f, OverlayPetPolicy.scaleFor(Float.NaN))
        assertEquals(0.50f, OverlayPetPolicy.SCALE_MIN)
        assertEquals(1.30f, OverlayPetPolicy.SCALE_MAX)
        listOf(0.50f, 0.70f, 0.78f, 0.85f, 0.93f, 1.00f, 1.20f, 1.30f).forEach { value ->
            val scale = OverlayPetPolicy.scaleFor(value)
            assertTrue(scale in OverlayPetPolicy.SCALE_MIN..OverlayPetPolicy.SCALE_MAX)
        }
    }

    @Test
    fun edgesTurnAroundInsteadOfSticking() {
        assertEquals(400f, OverlayPetPolicy.edgeTarget(10f, facing = -1, minX = 0f, maxX = 416f, mode = "walk"))
        assertEquals(16f, OverlayPetPolicy.edgeTarget(410f, facing = 1, minX = 0f, maxX = 416f, mode = "run"))
        assertNull(OverlayPetPolicy.edgeTarget(200f, facing = 1, minX = 0f, maxX = 416f, mode = "walk"))
        assertEquals(400f, OverlayPetPolicy.edgeTarget(10f, facing = 1, minX = 0f, maxX = 416f, mode = "idle"))
        assertNull(OverlayPetPolicy.edgeTarget(10f, facing = -1, minX = 0f, maxX = 416f, mode = "drag"))
    }

    @Test
    fun subpixelRightArrivalStillTurnsBack() {
        assertEquals(
            112f,
            OverlayPetPolicy.edgeTarget(351.862f, facing = 1, minX = 96f, maxX = 368f, mode = "run"),
        )
        assertEquals(
            112f,
            OverlayPetPolicy.edgeTarget(351.862f, facing = 1, minX = 96f, maxX = 368f, mode = "look"),
        )
    }

    @Test
    fun subpixelLeftArrivalStillTurnsBack() {
        assertEquals(
            352f,
            OverlayPetPolicy.edgeTarget(112.138f, facing = -1, minX = 96f, maxX = 368f, mode = "walk"),
        )
        assertEquals(
            352f,
            OverlayPetPolicy.edgeTarget(112.138f, facing = -1, minX = 96f, maxX = 368f, mode = "look"),
        )
    }

    @Test
    fun landingStaysOnTheFloor() {
        val (x, y) = OverlayPetPolicy.clampLanding(900f, 2000f, minX = 20f, maxX = 400f, floorY = 800f)
        assertEquals(400f, x)
        assertEquals(800f, y)
        assertEquals(40, OverlayPetPolicy.FLOOR_INSET_PX)
    }

    @Test
    fun sleepStartsOnlyAfterAQuietStretch() {
        assertFalse(OverlayPetPolicy.shouldStartSleep(1000, "idle", resting = false))
        assertTrue(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "walk", resting = false))
        assertFalse(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "walk", resting = true))
        assertFalse(OverlayPetPolicy.shouldStartSleep(OverlayPetPolicy.SLEEP_AFTER_MS, "drag", resting = false))
    }
}
