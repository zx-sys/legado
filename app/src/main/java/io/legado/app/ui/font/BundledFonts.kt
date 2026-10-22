package io.legado.app.ui.font

import android.content.Context
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import java.io.File

/**
 * 将 APK 内置的开源字体复制到字体目录，让 FontSelectDialog 直接可选。
 */
object BundledFonts {

    private const val ASSET_DIR = "font/presets"
    private val FONT_EXTENSIONS = listOf(".ttf", ".otf")

    fun ensureInstalled(context: Context): Int {
        val targetDir = File(FileUtils.getPath(context.externalFiles, "font"))
        targetDir.mkdirs()
        val names = context.assets.list(ASSET_DIR).orEmpty()
        var copied = 0
        names.filter { name ->
            FONT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        }.forEach { name ->
            val target = File(targetDir, name)
            if (!target.isFile || target.length() == 0L) {
                val temp = File(targetDir, "$name.tmp")
                temp.delete()
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                copied++
            }
        }
        return copied
    }
}
