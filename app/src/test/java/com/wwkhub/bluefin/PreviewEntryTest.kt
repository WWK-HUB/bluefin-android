package com.wwkhub.bluefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewEntryTest {
    @Test
    fun prefersRouteBWhenBothExist() {
        assertEquals(PreviewEntry.ROUTE_B, PreviewEntry.choose(hasRouteB = true, hasRouteA = true, forced = null))
    }

    @Test
    fun usesRouteAOnlyWhenBIsMissing() {
        assertEquals(PreviewEntry.ROUTE_A, PreviewEntry.choose(hasRouteB = false, hasRouteA = true, forced = null))
    }

    @Test
    fun staysOnRouteBWhenNothingIsPackaged() {
        assertEquals(PreviewEntry.ROUTE_B, PreviewEntry.choose(hasRouteB = false, hasRouteA = false, forced = null))
    }

    @Test
    fun forcedEntryWins() {
        assertEquals("custom/index.html", PreviewEntry.choose(true, true, "custom/index.html"))
    }

    @Test
    fun urlUsesAssetLoaderHost() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/bluefin/index.html",
            PreviewEntry.urlFor(PreviewEntry.ROUTE_B),
        )
    }

    @Test
    fun routeAIsMarkedSelfTest() {
        assertTrue(PreviewEntry.isSelfTest(PreviewEntry.ROUTE_A))
        assertFalse(PreviewEntry.isSelfTest(PreviewEntry.ROUTE_B))
    }

    @Test
    fun importedPackUsesThePackHostWithoutChangingRouteB() {
        assertEquals("bluefin/index.html", PreviewEntry.ROUTE_B)
        assertEquals(
            "https://appassets.androidplatform.net/assets/native/pack-host.html?pack=whale",
            PreviewEntry.packHostUrl("whale"),
        )
        assertEquals(
            "https://appassets.androidplatform.net/assets/native/pack-host.html?pack=whale&scheme=maid-casual",
            PreviewEntry.packHostUrl("whale", "maid-casual"),
        )
    }
}
