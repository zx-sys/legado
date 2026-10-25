package io.legado.app.help.tts

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 本地 TTS 选择：code 对应后台 TtsEngine，sid 对应引擎内音色。
 */
data class TtsSelection(
    @SerializedName("title") val title: String = "",
    @SerializedName("code") val code: String = "",
    @SerializedName("sid") val sid: Int = 0,
)

fun parseTtsSelection(raw: String?): TtsSelection? {
    if (raw.isNullOrBlank()) return null
    if (raw.toLongOrNull() != null) return null
    GSON.fromJsonObject<TtsSelection>(raw).getOrNull()
        ?.takeIf { it.code.isNotBlank() }
        ?.let { return it }
    // 兼容旧的 SelectItem 格式
    val legacy = GSON.fromJsonObject<SelectItem<String>>(raw).getOrNull() ?: return null
    val value = legacy.value?.trim().orEmpty()
    if (value.isBlank() || value.toLongOrNull() != null) return null
    return TtsSelection(title = legacy.title.orEmpty(), code = value, sid = 0)
}

data class TtsSpeaker(
    val id: Int,
    val name: String,
    val language: String,
)

data class TtsEngineConfig(
    @SerializedName("model_type") val modelType: String = "",
    @SerializedName("model") val model: String = "",
    @SerializedName("voices") val voices: String = "",
    @SerializedName("tokens") val tokens: String = "",
    @SerializedName("vocoder") val vocoder: String = "",
    @SerializedName("data_dir") val dataDir: String = "",
    @SerializedName("dict_dir") val dictDir: String = "",
    @SerializedName("archive_root") val archiveRoot: String = "",
    @SerializedName("rule_fsts") val ruleFsts: List<String> = emptyList(),
    @SerializedName("speaker_names") val speakerNames: List<String> = emptyList(),
    @SerializedName("speaker_count") val speakerCount: Int = 1,
    @SerializedName("sample_rate") val sampleRate: Int = 0,
    @SerializedName("num_ode_steps") val numOdeSteps: Int = 0,
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("archive") val archive: String = "",
    @SerializedName("lexicon") private val lexiconJson: JsonElement? = null,
) {
    val lexicons: List<String>
        get() = when {
            lexiconJson == null || lexiconJson.isJsonNull -> emptyList()
            lexiconJson.isJsonArray -> lexiconJson.asJsonArray.mapNotNull {
                if (it.isJsonPrimitive) it.asString else null
            }

            lexiconJson.isJsonPrimitive -> listOf(lexiconJson.asString)
            else -> emptyList()
        }

    val isKokoro: Boolean get() = modelType.startsWith("kokoro")
    val isMatcha: Boolean get() = modelType.startsWith("matcha")
}

data class RemoteTtsEngine(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("code") val code: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("engine_type") val engineType: String = "",
    @SerializedName("languages") val languages: List<String> = emptyList(),
    @SerializedName("speaker_count") val speakerCount: Int = 1,
    @SerializedName("version") val version: String = "",
    @SerializedName("license") val license: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("icon_url") val iconUrl: String = "",
    @SerializedName("package_url") val packageUrl: String = "",
    @SerializedName("package_size") val packageSize: Long = 0L,
    @SerializedName("config") val config: TtsEngineConfig = TtsEngineConfig(),
    @SerializedName("is_default") val isDefault: Boolean = false,
)

internal data class RemoteTtsEngineListResponse(
    @SerializedName("items") val items: List<RemoteTtsEngine> = emptyList(),
)

internal fun speakerLanguage(name: String): String = when {
    name.startsWith("zf_") || name.startsWith("zm_") -> "中文"
    name.startsWith("af_") || name.startsWith("am_") ||
            name.startsWith("bf_") || name.startsWith("bm_") -> "English"
    else -> ""
}
