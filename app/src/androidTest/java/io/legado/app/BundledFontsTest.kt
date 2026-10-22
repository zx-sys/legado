package io.legado.app

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.font.BundledFonts
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledFontsTest {

    @Test
    fun bundledFontsCanBeInstalledAndLoaded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BundledFonts.ensureInstalled(context)
        val fontDir = File(FileUtils.getPath(context.externalFiles, "font"))
        listOf("霞鹜文楷.ttf", "思源宋体.ttf", "朱雀仿宋.ttf", "得意黑.ttf").forEach { name ->
            val file = File(fontDir, name)
            assertTrue("missing bundled font: $name", file.isFile && file.length() > 0)
            assertNotNull("failed to load font: $name", Typeface.Builder(file).build())
        }
    }
}
