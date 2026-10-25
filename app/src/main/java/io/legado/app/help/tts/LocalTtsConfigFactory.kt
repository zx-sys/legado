package io.legado.app.help.tts

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * 按后台下发的 config 组装 sherpa-onnx 配置，支持 vits / kokoro / matcha。
 */
object LocalTtsConfigFactory {

    fun create(engine: RemoteTtsEngine): OfflineTtsConfig {
        val dir = RemoteTtsEngines.installDir(engine)
        val config = engine.config
        fun path(name: String): String = File(dir, name).absolutePath
        fun optionalDir(name: String): String =
            name.takeIf { it.isNotBlank() }?.let { path(it) } ?: ""

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val lexicon = config.lexicons
            .filter { it.isNotBlank() }
            .joinToString(",") { path(it) }

        val modelConfig = when {
            config.isKokoro -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = path(config.model),
                    voices = path(config.voices),
                    tokens = path(config.tokens),
                    dataDir = optionalDir(config.dataDir),
                    lexicon = lexicon,
                    dictDir = optionalDir(config.dictDir),
                ),
                numThreads = threads,
                provider = "cpu",
            )

            config.isMatcha -> OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = path(config.model),
                    vocoder = path(config.vocoder),
                    lexicon = lexicon,
                    tokens = path(config.tokens),
                    dataDir = optionalDir(config.dataDir),
                    dictDir = optionalDir(config.dictDir),
                ),
                numThreads = threads,
                provider = "cpu",
            )

            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = path(config.model),
                    lexicon = lexicon,
                    tokens = path(config.tokens),
                    dataDir = optionalDir(config.dataDir),
                    dictDir = optionalDir(config.dictDir),
                ),
                numThreads = threads,
                provider = "cpu",
            )
        }
        val ruleFsts = config.ruleFsts
            .filter { it.isNotBlank() }
            .joinToString(",") { path(it) }
        return OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = ruleFsts,
            maxNumSentences = 1,
        )
    }
}
