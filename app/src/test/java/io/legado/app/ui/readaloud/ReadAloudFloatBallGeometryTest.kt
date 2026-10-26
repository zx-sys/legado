package io.legado.app.ui.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudFloatBallGeometryTest {

    @Test
    fun `docks to the nearest horizontal edge`() {
        assertEquals(0f, ReadAloudFloatBallGeometry.dockX(0f, 100, 0f, 500f), 0f)
        assertEquals(0f, ReadAloudFloatBallGeometry.dockX(150f, 100, 0f, 500f), 0f)
        assertEquals(500f, ReadAloudFloatBallGeometry.dockX(350f, 100, 0f, 500f), 0f)
        assertEquals(500f, ReadAloudFloatBallGeometry.dockX(500f, 100, 0f, 500f), 0f)
    }

    @Test
    fun `keeps the ball above the reader floating bar`() {
        assertEquals(
            100f,
            ReadAloudFloatBallGeometry.maxTop(
                parentHeight = 800,
                insetBottom = 48,
                ballHeight = 100,
                reservedBottomPx = 552,
            ),
            0f,
        )
    }

    @Test
    fun `clamp tolerates an inverted range`() {
        assertEquals(10f, ReadAloudFloatBallGeometry.clamp(50f, 10f, 5f), 0f)
        assertEquals(5f, ReadAloudFloatBallGeometry.clamp(0f, 5f, 10f), 0f)
        assertEquals(10f, ReadAloudFloatBallGeometry.clamp(20f, 5f, 10f), 0f)
    }
}
