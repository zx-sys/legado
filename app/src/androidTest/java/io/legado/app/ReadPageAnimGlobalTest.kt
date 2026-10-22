package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.getPrefInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadPageAnimGlobalTest {

    @Test
    fun typographyIsGlobalButBackgroundStaysPerStyle() {
        val originalSize = ReadBookConfig.textSize
        val originalStyle = ReadBookConfig.styleSelect
        try {
            val backgrounds = ReadBookConfig.configList.map {
                Triple(it.bgStr, it.bgStrNight, it.bgStrEInk)
            }
            ReadBookConfig.textSize = originalSize + 3
            ReadBookConfig.normalizeGlobalLayout()
            ReadBookConfig.configList.forEach {
                assertEquals(originalSize + 3, it.textSize)
                assertEquals(ReadBookConfig.paddingLeft, it.paddingLeft)
            }
            assertEquals(
                "切换背景样式不应改变字号/边距/颜色",
                backgrounds,
                ReadBookConfig.configList.map { Triple(it.bgStr, it.bgStrNight, it.bgStrEInk) }
            )
        } finally {
            ReadBookConfig.textSize = originalSize
            ReadBookConfig.styleSelect = originalStyle
            ReadBookConfig.save()
        }
    }

    @Test
    fun pageAnimStaysGlobalWhenStyleChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalAnim = ReadBookConfig.pageAnim
        val originalStyle = ReadBookConfig.styleSelect
        try {
            ReadBookConfig.pageAnim = 2
            assertEquals(2, context.getPrefInt(PreferKey.readPageAnim, -1))
            if (ReadBookConfig.configList.size > 1) {
                ReadBookConfig.styleSelect =
                    (originalStyle + 1) % ReadBookConfig.configList.size
                assertEquals(
                    "页面动画应独立于背景样式",
                    2,
                    ReadBookConfig.pageAnim
                )
            }
        } finally {
            ReadBookConfig.pageAnim = originalAnim
            ReadBookConfig.styleSelect = originalStyle
            ReadBookConfig.save()
        }
    }
}
