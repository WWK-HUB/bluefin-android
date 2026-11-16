package com.wwkhub.bluefin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureScopeTest {
    @Test
    fun hidingAndShowingSelectedAppInvalidatesOldFrames() {
        val scope = ScreenCaptureScope()
        val visible = checkNotNull(scope.permit())
        scope.setVisible(true)
        assertTrue(scope.allows(visible))
        scope.setVisible(false)
        assertNull(scope.permit())
        assertFalse(scope.allows(visible))
        scope.setVisible(true)
        assertFalse(scope.allows(visible))
        assertTrue(scope.allows(checkNotNull(scope.permit())))
    }

    @Test
    fun stoppedProjectionCannotBeRestoredByLateVisibilityCallback() {
        val scope = ScreenCaptureScope()
        val permit = checkNotNull(scope.permit())
        scope.stop()
        scope.setVisible(false)
        scope.setVisible(true)
        assertNull(scope.permit())
        assertFalse(scope.allows(permit))
    }
}
