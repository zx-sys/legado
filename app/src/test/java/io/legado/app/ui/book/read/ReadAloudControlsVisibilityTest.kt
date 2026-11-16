package io.legado.app.ui.book.read

import org.junit.Assert.*
import org.junit.Test

class ReadAloudControlsVisibilityTest {
    @Test fun positionControlsStayAvailableWithDragSeek() {
        assertTrue(readAloudControlsVisible(true, false, false, true, true))
        assertTrue(readAloudControlsVisible(true, false, false, false, true))
    }
    @Test fun positionBarHidesWhileFollowing() {
        assertFalse(readAloudPositionBarVisible(following = true, positionEnabled = true))
        assertTrue(readAloudPositionBarVisible(following = false, positionEnabled = true))
        assertFalse(readAloudPositionBarVisible(following = false, positionEnabled = false))
    }
    @Test fun positionControlsWorkWhenPauseIsDisabled() {
        assertTrue(readAloudControlsVisible(true, false, false, false, true))
        assertFalse(readAloudControlsVisible(true, false, false, false, false))
    }
    @Test fun menuAutoHideAndStoppedServiceStillHideControls() {
        assertFalse(readAloudControlsVisible(true, true, false, true, true))
        assertFalse(readAloudControlsVisible(true, false, true, true, true))
        assertFalse(readAloudControlsVisible(false, false, false, true, true))
    }
}
