package io.legado.app.help.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动拉取时只有服务端严格更新才覆盖本地；本地更新或相同时保留本地并回传。
 */
class SyncProgressMergeTest {

    @Test
    fun `remote wins only when strictly newer`() {
        assertTrue(shouldApplyRemoteProgress(localUpdatedAt = 100, remoteUpdatedAt = 101))
        assertFalse(shouldApplyRemoteProgress(localUpdatedAt = 101, remoteUpdatedAt = 100))
        assertFalse(shouldApplyRemoteProgress(localUpdatedAt = 100, remoteUpdatedAt = 100))
    }
}
