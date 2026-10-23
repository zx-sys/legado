package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File

object DefaultData {

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    importDefaultTocRules()
                }
                if (LocalConfig.needUpRssSources) {
                    importDefaultRssSources()
                }
                if (LocalConfig.needUpDictRule) {
                    importDefaultDictRules()
                }
                if (LocalConfig.needUpBookSources) {
                    importDefaultBookSources()
                }
            }.onError {
                it.printOnDebug()
            }
        }
    }

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val themeConfigs: List<ThemeConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ThemeConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ThemeConfig.Config>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val dictRules: List<DictRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}dictRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    val bookSources: List<BookSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}bookSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<BookSource>(json).getOrElse {
            emptyList()
        }
    }

    private val removedBookSourceUrls: List<String> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}removedBookSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<String>(json).getOrElse { emptyList() }
    }

    /**
     * 清理历史版本导入的不可用默认书源（需登录/验证码/已失效）。
     */
    fun removeUnusableBookSourcesIfNeeded() {
        if (LocalConfig.unusableBookSourcesRemoved) return
        removedBookSourceUrls.forEach { appDb.bookSourceDao.delete(it) }
        appDb.bookSourceDao.getBookSource("https://www.lanlanbo.com")?.let { source ->
            if (source.bookSourceName.contains('\uFFFD')) {
                source.bookSourceName = "海棠搜书🔞蓝海搜书"
                appDb.bookSourceDao.insert(source)
            }
        }
        LocalConfig.unusableBookSourcesRemoved = true
    }

    private val addedDefaultBookSourceUrls = listOf(
        "https://www.suduguu.com",
        "http://www.chensiwx.info",
        "https://bcshuku.com/",
        "https://fanqienovel.com",
        "https://www.min-yuan.com",
        "https://m.libahao2.com",
        "https://www.deqixss.com/",
        "https://www.sudugu.cc",
        "https://www.banshanren.com",
        "https://apibi.cc",
        "https://api-bc.wtzw.com/#qimao-aggregate-md3",
        "https://www.huangjinwu.org/",
        "https://www.quanben.io",
        "https://www.zzbook.net",
        "https://www.shenwen.org",
        "https://wap.longshuwu.com",
        "http://www.3yt.info",
        "https://book.washai.net",
        "https://ik8.net",
        "http://www.gdbzkz.com",
        "https://www.sushujuan.org",
        "https://m.tkshuw.com",
        "https://m.xhwx8.com",
    )

    fun addNewDefaultBookSourcesIfNeeded() {
        if (LocalConfig.newDefaultBookSourcesAdded) return
        addedDefaultBookSourceUrls.forEach { url ->
            if (appDb.bookSourceDao.getBookSource(url) == null) {
                bookSources.find { it.bookSourceUrl == url }?.let {
                    appDb.bookSourceDao.insert(it)
                }
            }
        }
        LocalConfig.newDefaultBookSourcesAdded = true
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    fun importDefaultHttpTTS() {
        appDb.httpTTSDao.all
            .filter { it.id < 0 }
            .forEach { it.clearSharedGlobalState() }
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    fun importDefaultTocRules() {
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    fun importDefaultRssSources() {
        appDb.rssSourceDao.all
            .filter { it.sourceGroup == "legado" }
            .forEach { it.clearSharedGlobalState() }
        appDb.rssSourceDao.deleteDefault()
        appDb.rssSourceDao.insert(*rssSources.toTypedArray())
    }

    fun importDefaultDictRules() {
        appDb.dictRuleDao.insert(*dictRules.toTypedArray())
    }

    fun importDefaultBookSources() {
        appDb.bookSourceDao.insert(*bookSources.toTypedArray())
    }

}
