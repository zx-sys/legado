# AI 正文清洗客户端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 客户端把已缓存章节上传给后端，拉取“源级档案 + 逐章补丁”，直接改写章节缓存；拼接章节只提示不改写；全程可回退。

**Architecture:** 新增 Room 表 `aiChapterPatches` / `aiSourceProfiles`；`PolishManager` 监听缓存完成、批量上传、定时拉取；`SourceProfileApplier` + `AiPatchApplier` 在 IO 线程把“原始正文 → 源级归一化 → 逐章补丁”写到缓存；改写始终以 `.orig` 为基底，当前打开章节不落地。

**Tech Stack:** Kotlin、Room、OkHttp、GSON、LiveEventBus、协程。

**Spec:** `docs/superpowers/specs/2026-11-18-ai-content-polish-design.md`

## Global Constraints

- 默认关闭；用户手动开启后才上传/拉取。
- 只处理文本在线书；跳过卷名、本地书、音频、漫画、`isContentReversed` 章节。
- 分页/上传只在 WiFi 下进行（`aiPolishWifiOnly`，默认 true）。
- 改写前必须保留 `.orig`；源级规则启用时 `.orig` 不可关闭。
- 当前正在阅读的章节不改写；朗读会话正在读的章节也不改写；等离开该书或切到其他章后再落地。
- 服务端返回的 `start/end/from` 必须校验；任一锚点不命中整章放弃。
- 不把 DeepSeek Key 放进客户端；客户端只调后端。
- 构建：`./gradlew assembleAppDebug -Pbackup.server.url=http://118.31.79.174:9666`
- 单测：`./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.*"`

---

### Task 1: Room 实体、DAO 与迁移

**Files:**
- Create: `app/src/main/java/io/legado/app/data/entities/AiChapterPatch.kt`
- Create: `app/src/main/java/io/legado/app/data/entities/AiSourceProfileEntity.kt`
- Create: `app/src/main/java/io/legado/app/data/dao/AiChapterPatchDao.kt`
- Create: `app/src/main/java/io/legado/app/data/dao/AiSourceProfileDao.kt`
- Modify: `app/src/main/java/io/legado/app/data/AppDatabase.kt`

**Interfaces:**
- Consumes: Room
- Produces: `appDb.aiChapterPatchDao`、`appDb.aiSourceProfileDao`

- [ ] **Step 1: 写实体**

```kotlin
// app/src/main/java/io/legado/app/data/entities/AiChapterPatch.kt
package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "aiChapterPatches",
    indices = [Index(value = ["bookUrl", "chapterIndex"], unique = true)]
)
data class AiChapterPatch(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var bookUrl: String = "",
    var chapterIndex: Int = 0,
    var chapterUrl: String = "",
    var sourceHash: String = "",
    var appliedHash: String = "",
    var appliedTextHash: String = "",
    var status: String = "ok",          // ok / contaminated / suspicious
    var patchJson: String = "",
    var boundaryParagraph: Int = -1,
    var reason: String = "",
    var appliedProfileVersion: Int = 0,
    var restored: Boolean = false,
    var appliedAt: Long = 0,
    var updatedAt: Long = 0
)
```

```kotlin
// app/src/main/java/io/legado/app/data/entities/AiSourceProfileEntity.kt
package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aiSourceProfiles")
data class AiSourceProfileEntity(
    @PrimaryKey var sourceUrl: String = "",
    var version: Int = 0,
    var confidence: Double = 0.0,
    var profileJson: String = "",
    var updatedAt: Long = 0
)
```

- [ ] **Step 2: 写 DAO**

```kotlin
// app/src/main/java/io/legado/app/data/dao/AiChapterPatchDao.kt
package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiChapterPatch

@Dao
interface AiChapterPatchDao {
    @Query("select * from aiChapterPatches where bookUrl = :bookUrl")
    fun getByBook(bookUrl: String): List<AiChapterPatch>

    @Query("select * from aiChapterPatches where bookUrl = :bookUrl and chapterIndex = :index limit 1")
    fun getChapter(bookUrl: String, index: Int): AiChapterPatch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(patch: AiChapterPatch)

    @Query("delete from aiChapterPatches where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("select count(*) from aiChapterPatches where bookUrl = :bookUrl and appliedAt > 0")
    fun appliedCount(bookUrl: String): Int
}
```

```kotlin
// app/src/main/java/io/legado/app/data/dao/AiSourceProfileDao.kt
package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiSourceProfileEntity

@Dao
interface AiSourceProfileDao {
    @Query("select * from aiSourceProfiles where sourceUrl = :sourceUrl limit 1")
    fun get(sourceUrl: String): AiSourceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: AiSourceProfileEntity)

    @Query("delete from aiSourceProfiles where sourceUrl = :sourceUrl")
    fun delete(sourceUrl: String)
}
```

- [ ] **Step 3: 注册到 AppDatabase**

在 `AppDatabase.kt`：

```kotlin
version = 115,
entities = [..., AiChapterPatch::class, AiSourceProfileEntity::class],
autoMigrations = [
    ...
    AutoMigration(from = 114, to = 115),
]
```

并加入：

```kotlin
abstract val aiChapterPatchDao: AiChapterPatchDao
abstract val aiSourceProfileDao: AiSourceProfileDao
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/data
git commit -m "feat(polish): add chapter patch and source profile tables"
```

---

### Task 2: 设置项与设置界面

**Files:**
- Modify: `app/src/main/java/io/legado/app/constant/PreferKey.kt`
- Modify: `app/src/main/java/io/legado/app/help/config/AppConfig.kt`
- Modify: `app/src/main/res/xml/pref_config_other.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/io/legado/app/help/sync/SettingsSync.kt`

**Interfaces:**
- Produces: `AppConfig.aiPolishEnabled`、`aiPolishWifiOnly`、`aiPolishKeepOriginal`

- [ ] **Step 1: 加 key 与 getter**

在 `PreferKey` 追加：

```kotlin
const val aiPolishEnabled = "aiPolishEnabled"
const val aiPolishWifiOnly = "aiPolishWifiOnly"
const val aiPolishKeepOriginal = "aiPolishKeepOriginal"
const val aiPolishLastSync = "aiPolishLastSync"
```

在 `AppConfig` 追加：

```kotlin
var aiPolishEnabled by appCtx.prefBoolean(PreferKey.aiPolishEnabled, false)
var aiPolishWifiOnly by appCtx.prefBoolean(PreferKey.aiPolishWifiOnly, true)
var aiPolishKeepOriginal by appCtx.prefBoolean(PreferKey.aiPolishKeepOriginal, true)
```

如果项目里没有 `prefBoolean`/`prefInt` 委托，按现有写法展开：

```kotlin
var aiPolishEnabled: Boolean
    get() = appCtx.getPrefBoolean(PreferKey.aiPolishEnabled, false)
    set(value) = appCtx.putPrefBoolean(PreferKey.aiPolishEnabled, value)
```

其余三项同理（`getPrefInt/putPrefInt`）。

- [ ] **Step 2: 设置界面**

在 `pref_config_other.xml` 顶部加一个分类：

```xml
<io.legado.app.lib.prefs.PreferenceCategory
    android:title="@string/ai_polish"
    app:iconSpaceReserved="false">

    <io.legado.app.lib.prefs.SwitchPreference
        android:defaultValue="false"
        android:key="aiPolishEnabled"
        android:summary="@string/ai_polish_enabled_summary"
        android:title="@string/ai_polish_enabled"
        app:iconSpaceReserved="false" />

    <io.legado.app.lib.prefs.SwitchPreference
        android:defaultValue="true"
        android:key="aiPolishWifiOnly"
        android:summary="@string/ai_polish_wifi_only_summary"
        android:title="@string/ai_polish_wifi_only"
        app:iconSpaceReserved="false" />

    <io.legado.app.lib.prefs.SwitchPreference
        android:defaultValue="true"
        android:key="aiPolishKeepOriginal"
        android:summary="@string/ai_polish_keep_original_summary"
        android:title="@string/ai_polish_keep_original"
        app:iconSpaceReserved="false" />
</io.legado.app.lib.prefs.PreferenceCategory>
```

在 `values/strings.xml`（默认中文）追加：

```xml
<string name="ai_polish">AI 正文清洗</string>
<string name="ai_polish_enabled">启用 AI 清洗</string>
<string name="ai_polish_enabled_summary">上传已缓存章节，自动删广告、修缺字、识别拼接</string>
<string name="ai_polish_wifi_only">仅 WiFi 上传</string>
<string name="ai_polish_wifi_only_summary">避免移动流量</string>
<string name="ai_polish_keep_original">保留原文备份</string>
<string name="ai_polish_keep_original_summary">改写前保存 .orig，可随时还原</string>
<string name="ai_polish_notice_contaminated">本章疑似付费残缺/拼接，未做润色，建议换源</string>
```

- [ ] **Step 3: 同步排除**

在 `SettingsSync.kt` 的 `excludedKeys` 加：

```kotlin
"aiPolishLastSync",
```

预算、开关允许同步；游标按设备保留。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/constant/PreferKey.kt app/src/main/java/io/legado/app/help/config/AppConfig.kt app/src/main/res/xml/pref_config_other.xml app/src/main/res/values/strings.xml app/src/main/java/io/legado/app/help/sync/SettingsSync.kt
git commit -m "feat(polish): add ai polish settings"
```

---

### Task 3: 补丁应用器与位置映射

**Files:**
- Create: `app/src/main/java/io/legado/app/help/polish/AiPatchApplier.kt`
- Create: `app/src/main/java/io/legado/app/help/polish/AiPositionMapper.kt`
- Test: `app/src/test/java/io/legado/app/help/polish/AiPatchApplierTest.kt`
- Test: `app/src/test/java/io/legado/app/help/polish/AiPositionMapperTest.kt`

**Interfaces:**
- Produces:
  - `AiPatchApplier.apply(raw: String, patchJson: String): Result?`
  - `data class Edit(val start: Int, val end: Int, val replace: String)`
  - `AiPositionMapper.map(pos: Int, edits: List<Edit>): Int`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiPatchApplierTest {
    private val raw = "第一段。\n他走进了屋坐了下来。\n访问 www.e.com"

    @Test
    fun appliesSingleEdit() {
        val target = "他走进了屋坐了下来"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[
          {"p":1,"start":$start,"end":$end,"from":"$target","replace":"他走进了屋子，坐了下来"}
        ]}"""
        val result = AiPatchApplier.apply(raw, json)!!
        assertEquals("第一段。\n他走进了屋子，坐了下来。\n访问 www.e.com", result.text)
    }

    @Test
    fun rejectsAnchorMismatch() {
        val target = "他走进了屋坐了下来"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[{"p":1,"start":$start,"end":$end,"from":"不存在的","replace":"x"}]}"""
        assertNull(AiPatchApplier.apply(raw, json))
    }

    @Test
    fun rejectsOverlap() {
        val json = """{"edits":[
          {"p":0,"start":0,"end":3,"from":"第一段","replace":"甲"},
          {"p":0,"start":2,"end":4,"from":"段。","replace":"乙"}
        ]}"""
        assertNull(AiPatchApplier.apply(raw, json))
    }

    @Test
    fun deletion() {
        val target = "访问 www.e.com"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[{"p":2,"start":$start,"end":$end,"from":"$target","replace":""}]}"""
        val result = AiPatchApplier.apply(raw, json)!!
        assertEquals("第一段。\n他走进了屋坐了下来。\n", result.text)
    }
}
```

```kotlin
package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPositionMapperTest {
    @Test
    fun shiftAfterEdit() {
        val edits = listOf(AiPatchApplier.Edit(0, 3, "第一段正常"))
        assertEquals(18, AiPositionMapper.map(16, edits))
    }

    @Test
    fun insideEditClampsToStart() {
        val edits = listOf(AiPatchApplier.Edit(5, 10, "替换"))
        assertEquals(5, AiPositionMapper.map(7, edits))
    }

    @Test
    fun beforeEditUnchanged() {
        assertEquals(2, AiPositionMapper.map(2, listOf(AiPatchApplier.Edit(5, 10, "x"))))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.*"`
Expected: FAIL / unresolved reference

- [ ] **Step 3: 写实现**

```kotlin
package io.legado.app.help.polish

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object AiPatchApplier {

    data class Edit(val start: Int, val end: Int, val replace: String)

    data class Result(val text: String, val edits: List<Edit>)

    data class RawEdit(
        val p: Int = 0,
        val start: Int = 0,
        val end: Int = 0,
        val from: String = "",
        val replace: String = "",
    )

    data class Patch(val edits: List<RawEdit> = emptyList())

    fun apply(raw: String, patchJson: String): Result? {
        if (patchJson.isBlank()) return null
        val patch = GSON.fromJsonObject<Patch>(patchJson).getOrNull() ?: return null
        if (patch.edits.isEmpty()) return null

        val sorted = patch.edits.sortedBy { it.start }
        var lastEnd = -1
        for (e in sorted) {
            if (e.start < 0 || e.end < e.start || e.end > raw.length) return null
            if (e.start < lastEnd) return null
            if (raw.substring(e.start, e.end) != e.from) return null
            lastEnd = e.end
        }

        val builder = StringBuilder(raw)
        for (e in sorted.asReversed()) {
            builder.replace(e.start, e.end, e.replace)
        }
        return Result(
            text = builder.toString(),
            edits = sorted.map { Edit(it.start, it.end, it.replace) }
        )
    }
}
```

```kotlin
package io.legado.app.help.polish

object AiPositionMapper {

    fun map(pos: Int, edits: List<AiPatchApplier.Edit>): Int {
        var delta = 0
        for (e in edits.sortedBy { it.start }) {
            if (e.end <= pos) {
                delta += e.replace.length - (e.end - e.start)
            } else if (e.start < pos) {
                return (e.start + delta + (pos - e.start).coerceAtMost(e.replace.length))
                    .coerceAtLeast(0)
            }
        }
        return (pos + delta).coerceAtLeast(0)
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.*"`
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/help/polish app/src/test/java/io/legado/app/help/polish
git commit -m "feat(polish): apply server patches and map positions"
```

---

### Task 4: 源级档案应用器

**Files:**
- Create: `app/src/main/java/io/legado/app/help/polish/SourceProfileApplier.kt`
- Test: `app/src/test/java/io/legado/app/help/polish/SourceProfileApplierTest.kt`

**Interfaces:**
- Produces:
  - `SourceProfileApplier.apply(text: String, profileJson: String): Result`
  - `data class Result(val text: String, val edits: List<AiPatchApplier.Edit>)`

规则顺序必须与服务端一致：`char_map`（带例外）→ `context_rules` → `antonym_pairs`。

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceProfileApplierTest {

    private val profile = """
    {
      "char_map": {"乾": {"to": "干", "exceptions": ["乾坤", "乾隆"]}},
      "context_rules": [
        {"pattern": "([跑说唱跳])(很|非常|太)([\\u4e00-\\u9fa5])",
         "replacement": "${'$'}1得${'$'}2${'$'}3", "confidence": 0.9}
      ],
      "antonym_pairs": [{"from": "高", "to": "低", "confidence": 0.95}]
    }
    """.trimIndent()

    @Test
    fun charMapKeepsExceptions() {
        val r = SourceProfileApplier.apply("乾坤之间，乾杯", profile)
        assertTrue(r.text.contains("乾坤"))
        assertTrue(r.text.contains("干杯"))
    }

    @Test
    fun contextRule() {
        assertEquals("他跑得很快", SourceProfileApplier.apply("他跑很快", profile).text)
    }

    @Test
    fun antonym() {
        assertEquals("他很低", SourceProfileApplier.apply("他很高", profile).text)
    }

    @Test
    fun emptyProfile() {
        val r = SourceProfileApplier.apply("原文", "{}")
        assertEquals("原文", r.text)
        assertTrue(r.edits.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.SourceProfileApplierTest"`
Expected: FAIL / unresolved reference

- [ ] **Step 3: 写实现**

```kotlin
package io.legado.app.help.polish

import com.google.gson.annotations.SerializedName
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object SourceProfileApplier {

    data class Result(val text: String, val edits: List<AiPatchApplier.Edit>)

    data class CharMap(val to: String = "", val exceptions: List<String> = emptyList())
    data class ContextRule(
        val pattern: String = "",
        val replacement: String = "",
        val confidence: Double = 0.0,
    )
    data class Antonym(
        val from: String = "",
        val to: String = "",
        val confidence: Double = 0.0,
    )
    data class Profile(
        @SerializedName("char_map") val charMap: Map<String, CharMap>? = null,
        @SerializedName("context_rules") val contextRules: List<ContextRule>? = null,
        @SerializedName("antonym_pairs") val antonymPairs: List<Antonym>? = null,
    )

    fun apply(text: String, profileJson: String): Result {
        if (profileJson.isBlank()) return Result(text, emptyList())
        val profile = GSON.fromJsonObject<Profile>(profileJson).getOrNull()
            ?: return Result(text, emptyList())

        var current = text
        val edits = mutableListOf<AiPatchApplier.Edit>()

        profile.charMap?.forEach { (src, cfg) ->
            if (src.isEmpty() || cfg.to.isEmpty()) return@forEach
            current = replaceWithOffsets(current, src, cfg.to, cfg.exceptions, edits)
        }

        profile.contextRules?.sortedByDescending { it.confidence }?.forEach { rule ->
            if (rule.pattern.isEmpty()) return@forEach
            val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: return@forEach
            val replacements = regex.findAll(current).map { m ->
                val replacement = expand(rule.replacement, m)
                AiPatchApplier.Edit(m.range.first, m.range.last + 1, replacement)
            }.toList()
            if (replacements.isNotEmpty()) {
                current = applyEdits(current, replacements)
                edits += replacements
            }
        }

        profile.antonymPairs?.forEach { pair ->
            if (pair.confidence < 0.9 || pair.from.isEmpty() || pair.to.isEmpty()) return@forEach
            current = replaceWithOffsets(current, pair.from, pair.to, emptyList(), edits)
        }

        return Result(current, edits)
    }

    private fun replaceWithOffsets(
        text: String,
        src: String,
        dst: String,
        exceptions: List<String>,
        out: MutableList<AiPatchApplier.Edit>,
    ): String {
        val protected = exceptions.filter { it.isNotEmpty() }
        val edits = mutableListOf<AiPatchApplier.Edit>()
        var i = 0
        while (i <= text.length - src.length) {
            val hit = text.startsWith(src, i)
            val inException = protected.any { text.startsWith(it, i) }
            if (hit && !inException) {
                edits += AiPatchApplier.Edit(i, i + src.length, dst)
                i += src.length
            } else {
                i++
            }
        }
        if (edits.isEmpty()) return text
        out += edits
        return applyEdits(text, edits)
    }

    private fun applyEdits(text: String, edits: List<AiPatchApplier.Edit>): String {
        val b = StringBuilder(text)
        for (e in edits.sortedByDescending { it.start }) {
            if (e.end <= b.length) b.replace(e.start, e.end, e.replace)
        }
        return b.toString()
    }

    private fun expand(template: String, match: MatchResult): String {
        return Regex("\\$(\\d)").replace(template) { m ->
            match.groupValues.getOrNull(m.groupValues[1].toInt()) ?: ""
        }
    }
}
```

`char_map` / `context_rules` / `antonym_pairs` 已用 `@SerializedName` 对齐服务端字段；不要改成 Kotlin 驼峰名，否则 GSON 读不到。

- [ ] **Step 4: 运行测试**

Run: `./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.SourceProfileApplierTest"`
Expected: 4 tests PASS

- [ ] **Step 5: 与服务端对拍**

把服务端 `apply_profile` 的四组测试输入复制成 Kotlin 测试，断言两边输出完全一致；不一致就修客户端（以服务端为准）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/legado/app/help/polish/SourceProfileApplier.kt app/src/test/java/io/legado/app/help/polish/SourceProfileApplierTest.kt
git commit -m "feat(polish): apply source profile rules on client"
```

---

### Task 5: 后端 API 客户端

**Files:**
- Create: `app/src/main/java/io/legado/app/help/polish/PolishApi.kt`

**Interfaces:**
- Consumes: `BackupServer.apiBase`、`okHttpClient`
- Produces:
  - `PolishApi.upload(body: UploadRequest): UploadResponse`
  - `PolishApi.patches(bookUrl: String, since: Long): PatchResponse`
  - `PolishApi.sourceProfile(sourceUrl: String): SourceProfileResponse?`
  - `PolishApi.status(bookUrl: String): StatusResponse`

- [ ] **Step 1: 写数据类与请求**

```kotlin
package io.legado.app.help.polish

import com.google.gson.annotations.SerializedName
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class PolishChapter(
    @SerializedName("index") val index: Int,
    @SerializedName("url") val url: String,
    @SerializedName("title") val title: String,
    @SerializedName("hash") val hash: String,
    @SerializedName("text") val text: String,
)

data class PolishUploadRequest(
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("bookName") val bookName: String,
    @SerializedName("bookAuthor") val bookAuthor: String,
    @SerializedName("sourceUrl") val sourceUrl: String,
    @SerializedName("priority") val priority: Int,
    @SerializedName("profileVersion") val profileVersion: Int,
    @SerializedName("chapters") val chapters: List<PolishChapter>,
)

data class PolishUploadResponse(
    @SerializedName("accepted") val accepted: Int = 0,
    @SerializedName("deduped") val deduped: Int = 0,
    @SerializedName("skipped") val skipped: Int = 0,
)

data class PolishPatchItem(
    @SerializedName("chapterIndex") val chapterIndex: Int,
    @SerializedName("chapterUrl") val chapterUrl: String,
    @SerializedName("sourceHash") val sourceHash: String,
    @SerializedName("status") val status: String,
    @SerializedName("boundary") val boundary: Int? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("patch") val patch: Any? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0,
)

data class PolishPatchResponse(
    @SerializedName("patches") val patches: List<PolishPatchItem> = emptyList(),
)

data class PolishSourceProfileResponse(
    @SerializedName("sourceUrl") val sourceUrl: String,
    @SerializedName("version") val version: Int,
    @SerializedName("confidence") val confidence: Double = 0.0,
    @SerializedName("profile") val profile: Any? = null,
)

data class PolishStatusResponse(
    @SerializedName("queued") val queued: Int = 0,
    @SerializedName("done") val done: Int = 0,
    @SerializedName("contaminated") val contaminated: Int = 0,
    @SerializedName("suspicious") val suspicious: Int = 0,
    @SerializedName("failed") val failed: Int = 0,
    @SerializedName("bookStatus") val bookStatus: String = "normal",
    @SerializedName("budgetLeftMicros") val budgetLeftMicros: Long = 0,
)

object PolishApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy { okHttpClient }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url("${BackupServer.apiBase}$path")
        if (BackupServer.phone.isNotEmpty()) b.header("X-User-Phone", BackupServer.phone)
        return b
    }

    suspend fun upload(body: PolishUploadRequest): PolishUploadResponse =
        withContext(Dispatchers.IO) {
            val req = builder("/polish/jobs")
                .post(GSON.toJson(body).toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishUploadResponse>(text).getOrThrow()
            }
        }

    suspend fun patches(bookUrl: String, since: Long): PolishPatchResponse =
        withContext(Dispatchers.IO) {
            val url = "/polish/patches?bookUrl=${java.net.URLEncoder.encode(bookUrl, "UTF-8")}&since=$since"
            client.newCall(builder(url).get().build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishPatchResponse>(text).getOrThrow()
            }
        }

    suspend fun sourceProfile(sourceUrl: String): PolishSourceProfileResponse? =
        withContext(Dispatchers.IO) {
            val url = "/polish/source-profile?sourceUrl=${java.net.URLEncoder.encode(sourceUrl, "UTF-8")}"
            client.newCall(builder(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body.string()
                if (text.isBlank() || text == "{}") return@use null
                GSON.fromJsonObject<PolishSourceProfileResponse>(text).getOrNull()
            }
        }

    suspend fun status(bookUrl: String): PolishStatusResponse =
        withContext(Dispatchers.IO) {
            val url = "/polish/status?bookUrl=${java.net.URLEncoder.encode(bookUrl, "UTF-8")}"
            client.newCall(builder(url).get().build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishStatusResponse>(text).getOrThrow()
            }
        }
}
```

注意：`patch` / `profile` 用 `Any?` 接收后再 `GSON.toJson()` 落库，避免为每个补丁建强类型。

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/legado/app/help/polish/PolishApi.kt
git commit -m "feat(polish): add backend polish api client"
```

---

### Task 6: 上传器与事件触发

**Files:**
- Create: `app/src/main/java/io/legado/app/help/polish/PolishUploader.kt`
- Create: `app/src/main/java/io/legado/app/help/polish/PolishManager.kt`
- Modify: `app/src/main/java/io/legado/app/App.kt`（`onCreate` 初始化）

**Interfaces:**
- Consumes: `appDb.bookChapterDao`、`BookHelp.getContent`、`BookHelp.isContentReversed`
- Produces:
  - `PolishUploader.collect(book: Book, limit: Int = 10): List<PolishChapter>`
  - `PolishManager.init()`、`PolishManager.scheduleBook(book)`

- [ ] **Step 1: 写上传收集器**

```kotlin
package io.legado.app.help.polish

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.MD5Utils

object PolishUploader {

    fun collect(book: Book, limit: Int = 10): List<PolishChapter> {
        if (book.type and BookType.text == 0) return emptyList()
        if (book.type and (BookType.local or BookType.audio or BookType.image or BookType.video) != 0) {
            return emptyList()
        }
        if (book.origin == BookType.localTag) return emptyList()
        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
            .associateBy { it.chapterIndex }
        val profile = appDb.aiSourceProfileDao.get(book.origin)
        val profileJson = profile?.profileJson ?: ""
        val version = profile?.version ?: 0
        val result = mutableListOf<PolishChapter>()

        for (chapter in appDb.bookChapterDao.getChapterList(book.bookUrl)) {
            if (result.size >= limit) break
            if (chapter.isVolume) continue
            if (BookHelp.isContentReversed(book, chapter)) continue
            // Task 7 加完 .orig 支持后改成 BookHelp.readOriginal(book, chapter)
            val raw = BookHelp.getContent(book, chapter) ?: continue
            if (raw.isBlank()) continue
            val normalized = if (profileJson.isBlank()) raw
            else SourceProfileApplier.apply(raw, profileJson).text
            val hash = MD5Utils.md5Encode("$version\n$normalized")
            val existing = patches[chapter.index]
            if (existing != null && (existing.sourceHash == hash || existing.appliedHash == hash)) {
                continue
            }
            result += PolishChapter(
                index = chapter.index,
                url = chapter.url,
                title = chapter.title,
                hash = hash,
                text = normalized,
            )
        }
        return result
    }

    fun isEligible(chapter: BookChapter, reversed: Boolean): Boolean =
        !chapter.isVolume && !reversed
}
```

- [ ] **Step 2: 写管理器**

```kotlin
package io.legado.app.help.polish

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

object PolishManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()

    fun init() {
        LiveEventBus.get<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT)
            .observeForever { (book, _) -> scheduleBook(book) }
    }

    fun scheduleBook(book: Book) {
        if (!AppConfig.aiPolishEnabled) return
        pending[book.bookUrl]?.cancel()
        pending[book.bookUrl] = scope.launch {
            delay(30_000)
            uploadBook(book)
        }
    }

    fun canUseNetwork(): Boolean {
        if (!AppConfig.aiPolishWifiOnly) return true
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun uploadBook(book: Book) {
        if (!AppConfig.aiPolishEnabled) return
        if (!BackupServer.serverUrlConfigured || BackupServer.phone.isEmpty()) return
        if (!canUseNetwork()) return
        val profile = appDb.aiSourceProfileDao.get(book.origin)
        val chapters = PolishUploader.collect(book)
        if (chapters.isEmpty()) return
        PolishApi.upload(
            PolishUploadRequest(
                bookUrl = book.bookUrl,
                bookName = book.name,
                bookAuthor = book.author,
                sourceUrl = book.origin,
                priority = 0,
                profileVersion = profile?.version ?: 0,
                chapters = chapters,
            )
        )
    }
}
```

在 `App.onCreate()` 里 `LiveEventBus.config()` 之后调用 `PolishManager.init()`。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/legado/app/help/polish/PolishUploader.kt app/src/main/java/io/legado/app/help/polish/PolishManager.kt app/src/main/java/io/legado/app/App.kt
git commit -m "feat(polish): upload cached chapters on save"
```

---

### Task 7: 拉取、改写缓存与位置修正

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/book/BookHelp.kt`
- Create: `app/src/main/java/io/legado/app/help/polish/PolishApplyWorker.kt`
- Modify: `app/src/main/java/io/legado/app/help/polish/PolishManager.kt`

**Interfaces:**
- Consumes: `AiPatchApplier`、`SourceProfileApplier`、`AiPositionMapper`
- Produces:
  - `BookHelp.readOriginal(book, chapter): String?`
  - `BookHelp.backupOriginal(book, chapter): Boolean`
  - `BookHelp.restoreOriginal(book, chapter): Boolean`
  - `PolishApplyWorker.applyBook(book)`

- [ ] **Step 1: 在 BookHelp 加备份/读取/还原**

```kotlin
fun readOriginal(book: Book, chapter: BookChapter): String? {
    val fileName = contentSaveFileName(book, chapter) ?: chapter.getFileName()
    val backup = File(
        downloadDir.getFile(cacheFolderName, book.getFolderName(), fileName).path + ".orig"
    )
    return if (backup.isFile) backup.readText() else getContent(book, chapter)
}

fun backupOriginal(book: Book, chapter: BookChapter): Boolean {
    val fileName = contentSaveFileName(book, chapter) ?: chapter.getFileName()
    val file = downloadDir.getFile(cacheFolderName, book.getFolderName(), fileName)
    if (!file.isFile) return false
    val backup = File(file.path + ".orig")
    if (!backup.exists()) file.copyTo(backup, overwrite = false)
    return true
}

fun restoreOriginal(book: Book, chapter: BookChapter): Boolean {
    val fileName = contentSaveFileName(book, chapter) ?: chapter.getFileName()
    val backup = File(
        downloadDir.getFile(cacheFolderName, book.getFolderName(), fileName).path + ".orig"
    )
    if (!backup.isFile) return false
    saveText(book, chapter, backup.readText())
    return true
}
```

另外，在 `BookHelp.saveContent` 成功写入**新抓取原文**后删除同章 `.orig`：

```kotlin
// saveContent 内，contentSaveFence.writeIfCurrent(...) 返回 saved 之后
if (saved) {
    val fileName = bookChapter.getFileName()
    File(
        downloadDir.getFile(cacheFolderName, token.folderName, fileName).path + ".orig"
    ).delete()
}
```

`saveText`（清洗改写）**不能**删除 `.orig`。这样换源/刷新章节后，新原文会成为基底，不会被旧原文的补丁覆盖。

同时把 `PolishUploader.collect` 里的 `BookHelp.getContent(book, chapter)` 改成 `BookHelp.readOriginal(book, chapter)`，保证上传的是原文而不是已改写文本。

- [ ] **Step 2: 写应用 worker**

```kotlin
package io.legado.app.help.polish

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils

object PolishApplyWorker {

    fun applyBook(book: Book) {
        val profileEntity = appDb.aiSourceProfileDao.get(book.origin)
        val profileJson = profileEntity?.profileJson ?: ""
        val profileVersion = profileEntity?.version ?: 0
        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
        val currentIndex = if (ReadBook.book?.bookUrl == book.bookUrl) {
            ReadBook.durChapterIndex
        } else -1
        val ttsIndex = if (ReadAloud.isReadingBook(book.bookUrl)) {
            ReadAloud.readAloudChapterIndex
        } else -1

        for (patch in patches) {
            if (patch.status != "ok" || patch.patchJson.isBlank() || patch.restored) continue
            if (patch.chapterIndex == currentIndex || patch.chapterIndex == ttsIndex) continue
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, patch.chapterIndex)
                ?: continue
            if (BookHelp.isContentReversed(book, chapter)) continue
            val current = BookHelp.getContent(book, chapter) ?: continue
            if (patch.appliedAt > 0 &&
                patch.appliedProfileVersion == profileVersion &&
                patch.appliedTextHash == MD5Utils.md5Encode(current)
            ) {
                continue
            }
            val raw = BookHelp.readOriginal(book, chapter) ?: current
            val profileResult = if (profileJson.isBlank()) {
                SourceProfileApplier.Result(raw, emptyList())
            } else {
                SourceProfileApplier.apply(raw, profileJson)
            }
            val normalized = profileResult.text
            val hash = MD5Utils.md5Encode("$profileVersion\n$normalized")
            if (hash != patch.sourceHash) continue
            val patchResult = AiPatchApplier.apply(normalized, patch.patchJson) ?: continue
            BookHelp.backupOriginal(book, chapter)
            BookHelp.saveText(book, chapter, patchResult.text)
            patch.appliedHash = hash
            patch.appliedTextHash = MD5Utils.md5Encode(patchResult.text)
            patch.appliedProfileVersion = profileVersion
            patch.appliedAt = System.currentTimeMillis()
            appDb.aiChapterPatchDao.upsert(patch)
            adjustPositions(book, chapter.index, profileResult.edits + patchResult.edits)
        }
    }

    private fun adjustPositions(
        book: Book,
        chapterIndex: Int,
        edits: List<AiPatchApplier.Edit>,
    ) {
        if (edits.isEmpty()) return
        if (book.durChapterIndex == chapterIndex) {
            book.durChapterPos = AiPositionMapper.map(book.durChapterPos, edits)
            appDb.bookDao.update(book)
        }
        appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter { it.chapterIndex == chapterIndex }
            .forEach {
                it.chapterPos = AiPositionMapper.map(it.chapterPos, edits)
                appDb.bookmarkDao.update(it)
            }
        appDb.bookHighlightDao.getByBook(book.bookUrl)
            .filter { it.chapterIndex == chapterIndex }
            .forEach {
                it.chapterPos = AiPositionMapper.map(it.chapterPos, edits)
                it.chapterPosEnd = AiPositionMapper.map(it.chapterPosEnd, edits)
                appDb.bookHighlightDao.update(it)
            }
    }
}
```

- [ ] **Step 3: 无补丁章节在渲染时应用源级规则**

新增 `PolishProfileCache`：

```kotlin
package io.legado.app.help.polish

import io.legado.app.data.appDb
import java.util.concurrent.ConcurrentHashMap

object PolishProfileCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun put(origin: String, profileJson: String) {
        if (profileJson.isBlank()) cache.remove(origin)
        else cache[origin] = profileJson
    }

    fun get(origin: String): String? {
        cache[origin]?.let { return it }
        return appDb.aiSourceProfileDao.get(origin)?.profileJson?.also { cache[origin] = it }
    }

    fun invalidate(origin: String) {
        cache.remove(origin)
    }
}
```

在 `ContentProcessor.getContent` 的 `var mContent = content` 之后加入：

```kotlin
if (AppConfig.aiPolishEnabled && book.origin.isNotEmpty()) {
    val patch = appDb.aiChapterPatchDao.getChapter(book.bookUrl, chapter.index)
    if (patch == null || patch.appliedAt == 0L) {
        PolishProfileCache.get(book.origin)?.let { profile ->
            mContent = SourceProfileApplier.apply(mContent, profile).text
        }
    }
}
```

逻辑：有已落地补丁的章节，缓存文件已经是“源级规则 + 补丁”的结果，渲染时不再套规则；没有补丁的章节，缓存保持原文，渲染时免费套规则。这样不必为了源级规则重写整本缓存。

- [ ] **Step 4: 拉取并应用到 Manager**

在 `PolishManager` 追加：

```kotlin
suspend fun syncBook(book: Book) {
    if (!AppConfig.aiPolishEnabled) return
    if (!BackupServer.serverUrlConfigured || BackupServer.phone.isEmpty()) return
    if (!canUseNetwork()) return
    val profile = PolishApi.sourceProfile(book.origin)
    if (profile != null) {
        appDb.aiSourceProfileDao.upsert(
            io.legado.app.data.entities.AiSourceProfileEntity(
                sourceUrl = profile.sourceUrl,
                version = profile.version,
                confidence = profile.confidence,
                profileJson = GSON.toJson(profile.profile),
                updatedAt = System.currentTimeMillis(),
            )
        )
        PolishProfileCache.put(book.origin, GSON.toJson(profile.profile))
    }
    val since = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
        .maxOfOrNull { it.updatedAt } ?: 0L
    val response = PolishApi.patches(book.bookUrl, since)
    for (item in response.patches) {
        val existing = appDb.aiChapterPatchDao.getChapter(book.bookUrl, item.chapterIndex)
        val sameSource = existing?.sourceHash == item.sourceHash
        appDb.aiChapterPatchDao.upsert(
            io.legado.app.data.entities.AiChapterPatch(
                id = existing?.id ?: 0,
                bookUrl = book.bookUrl,
                chapterIndex = item.chapterIndex,
                chapterUrl = item.chapterUrl,
                sourceHash = item.sourceHash,
                appliedHash = if (sameSource) existing!!.appliedHash else "",
                appliedTextHash = if (sameSource) existing!!.appliedTextHash else "",
                status = if (item.status == "done") "ok" else item.status,
                patchJson = item.patch?.let { GSON.toJson(it) } ?: "",
                boundaryParagraph = item.boundary ?: -1,
                reason = item.reason,
                appliedProfileVersion = if (sameSource) existing!!.appliedProfileVersion else 0,
                restored = if (sameSource) existing!!.restored else false,
                appliedAt = if (sameSource) existing!!.appliedAt else 0,
                updatedAt = item.updatedAt,
            )
        )
    }
    PolishApplyWorker.applyBook(book)
}

suspend fun syncAndApply(book: Book) {
    runCatching { syncBook(book) }
}
```

- [ ] **Step 5: 在阅读页/详情页触发**

在 `BookInfoActivity.onResume()` 和 `ReadBookActivity.onResume()` 中：

```kotlin
// BookInfoActivity.onResume()
viewModel.getBook()?.let { book ->
    lifecycleScope.launch(Dispatchers.IO) { PolishManager.syncAndApply(book) }
}

// ReadBookActivity.onResume()
ReadBook.book?.let { book ->
    lifecycleScope.launch(Dispatchers.IO) { PolishManager.syncAndApply(book) }
}
```

- [ ] **Step 6: 编译**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 单测**

Run: `./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.help.polish.*"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/legado/app/help/book/BookHelp.kt app/src/main/java/io/legado/app/help/polish app/src/main/java/io/legado/app/ui/book
git commit -m "feat(polish): pull patches and rewrite cached chapters"
```

---

### Task 8: 书籍详情入口与拼接提示

**Files:**
- Modify: `app/src/main/res/menu/book_info.xml`
- Modify: `app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt`
- Create: `app/src/main/java/io/legado/app/ui/book/info/AiPolishDialog.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/help/polish/PolishManager.kt`

**Interfaces:**
- Produces: `PolishManager.noticeIfContaminated(context, book, chapterIndex)`

- [ ] **Step 1: 加菜单项**

在 `book_info.xml` 追加：

```xml
<item
    android:id="@+id/menu_ai_polish"
    android:title="@string/ai_polish"
    app:showAsAction="never" />
```

在 `BookInfoActivity.onCompatCreateOptionsMenu` 后无需额外处理；在 `onOptionsItemSelected` 加：

```kotlin
R.id.menu_ai_polish -> AiPolishDialog.show(this, viewModel.getBook()!!)
```

- [ ] **Step 2: 写对话框**

```kotlin
package io.legado.app.ui.book.info

import android.content.Context
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.polish.PolishManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx

object AiPolishDialog {

    fun show(context: Context, book: Book) {
        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
        val done = patches.count { it.status == "ok" && it.appliedAt > 0 }
        val contaminated = patches.count { it.status == "contaminated" || it.status == "suspicious" }
        val message = context.getString(R.string.ai_polish_status, done, contaminated)
        AlertDialog.Builder(context)
            .setTitle(R.string.ai_polish)
            .setMessage(message)
            .setPositiveButton(R.string.ai_polish_immediate) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    PolishManager.clearRestored(book)
                    PolishManager.uploadBook(book)
                    PolishManager.syncAndApply(book)
                }
            }
            .setNeutralButton(R.string.ai_polish_restore) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    PolishManager.restoreBook(book)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
```

在 `strings.xml` 追加：

```xml
<string name="ai_polish_status">已清洗 %1$d 章，疑似拼接 %2$d 章</string>
<string name="ai_polish_immediate">立即清洗</string>
<string name="ai_polish_restore">还原原文</string>
```

- [ ] **Step 3: 还原**

在 `PolishManager` 追加：

```kotlin
suspend fun restoreBook(book: Book) {
    for (patch in appDb.aiChapterPatchDao.getByBook(book.bookUrl)) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, patch.chapterIndex) ?: continue
        BookHelp.restoreOriginal(book, chapter)
        patch.restored = true
        patch.appliedAt = 0
        patch.appliedHash = ""
        patch.appliedTextHash = ""
        appDb.aiChapterPatchDao.upsert(patch)
    }
}

suspend fun clearRestored(book: Book) {
    for (patch in appDb.aiChapterPatchDao.getByBook(book.bookUrl)) {
        if (patch.restored) {
            patch.restored = false
            appDb.aiChapterPatchDao.upsert(patch)
        }
    }
}
```

- [ ] **Step 4: 阅读页提示**

在 `PolishManager` 追加：

```kotlin
private val noticed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

fun noticeIfContaminated(book: Book, chapterIndex: Int, show: (String) -> Unit) {
    val patch = appDb.aiChapterPatchDao.getChapter(book.bookUrl, chapterIndex) ?: return
    if (patch.status != "contaminated" && patch.status != "suspicious") return
    val key = "${book.bookUrl}:$chapterIndex"
    if (!noticed.add(key)) return
    show(appCtx.getString(io.legado.app.R.string.ai_polish_notice_contaminated))
}
```

在 `ReadBookActivity.upContent` 末尾（`success?.invoke()` 之前）调用：

```kotlin
ReadBook.book?.let { book ->
    PolishManager.noticeIfContaminated(book, ReadBook.durChapterIndex) { text ->
        toastOnUi(text)
    }
}
```

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/menu/book_info.xml app/src/main/java/io/legado/app/ui/book/info app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt app/src/main/java/io/legado/app/help/polish/PolishManager.kt app/src/main/res/values/strings.xml
git commit -m "feat(polish): add book info entry and spliced chapter notice"
```

---

### Task 9: 模拟器端到端验证

**Files:** 无代码改动

- [ ] **Step 1: 构建**

Run:

```bash
./gradlew assembleAppDebug -Pbackup.server.url=http://118.31.79.174:9666
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 安装并开启**

安装 APK → 登录/绑定手机号 → 设置 → AI 正文清洗 → 开启（仅 WiFi、保留原文）。

- [ ] **Step 3: 准备一本书**

选一本已缓存 10 章以上的文本在线书，确认 `/sdcard/Android/data/io.legato.app/files/cache/...` 下有章节文件（或通过 App 的缓存目录查看）。

- [ ] **Step 4: 触发上传**

打开书籍详情 → 菜单 → AI 正文清洗 → 立即清洗；或用 `adb logcat` 确认请求 `/api/legado/app/polish/jobs` 返回 200。

- [ ] **Step 5: 等待补丁**

在服务端空闲窗口执行：

```bash
cd backend && .venv/Scripts/python.exe manage.py run_polish_worker --once --limit 5
```

- [ ] **Step 6: 验证改写**

App 内重新打开书籍详情触发 `syncBook`；检查：

- 缓存目录出现 `*.orig`；
- 章节正文中的 URL/乱码消失；
- 当前正在读的章节**没有**被改写；
- 进度、书签、高亮位置未明显偏移；
- 拼接章节弹出提示，缓存文件未变化。

- [ ] **Step 7: 验证回退**

点击“还原原文”，确认缓存文件与 `.orig` 一致，补丁表清空。

- [ ] **Step 8: 记录结果**

把设备、书源、章节、费用、差异截图路径写入设计文档“验收”小节。

---

### Task 10: 灰度与开关

**Files:** 无代码改动

- [ ] **Step 1: 默认关闭**

确认 `aiPolishEnabled` 默认 false；新用户不会自动上传。

- [ ] **Step 2: 小范围开启**

先只在自己设备开启，跑 1 本书、20 章，人工对照原文确认误改率。

- [ ] **Step 3: 观察指标**

- 上传失败率；
- 补丁应用失败率（锚点不命中）；
- 拼接判定误报；
- 单章平均费用；
- 用户是否反馈阅读位置跳变。

- [ ] **Step 4: 扩大范围**

指标正常后再默认提示用户开启；保留“还原原文”入口。
