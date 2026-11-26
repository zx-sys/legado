package io.legado.app.help.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecordRemovalTest {

    @Test
    fun `older local record is removed by tombstone`() {
        assertTrue(shouldApplyRecordRemoval(localLastRead = 100, removalUpdatedAt = 200))
    }

    @Test
    fun `record read after deletion survives tombstone`() {
        assertFalse(shouldApplyRecordRemoval(localLastRead = 300, removalUpdatedAt = 200))
    }
}
