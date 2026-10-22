package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NeuralTtsEngineTest {

    @Test
    fun generateMixedChineseEnglish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.getExternalFilesDir("tts"), "vits-melo-tts-zh_en")
        val requiredFiles = listOf(
            "model.onnx",
            "lexicon.txt",
            "tokens.txt",
            "date.fst",
            "number.fst",
            "phone.fst",
        )
        val missingFiles = requiredFiles.filter { !File(modelDir, it).isFile }
        assumeTrue(
            "model not installed at $modelDir, missing: $missingFiles",
            missingFiles.isEmpty()
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(modelDir, "model.onnx").absolutePath,
                    lexicon = File(modelDir, "lexicon.txt").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            ruleFsts = listOf("date.fst", "number.fst", "phone.fst")
                .joinToString(",") { File(modelDir, it).absolutePath },
            maxNumSentences = 1,
        )
        val tts = OfflineTts(assetManager = null, config = config)
        val sampleRate = tts.sampleRate()
        assertTrue("invalid sample rate", sampleRate > 0)
        val audio = tts.generate("这是 Legado 的 offline TTS 测试。", 0, 1.0f)
        assertTrue("empty audio", audio.samples.isNotEmpty())
        val output = File(context.getExternalFilesDir("tts"), "neural-tts-test.wav")
        assertTrue("failed to save wav", audio.save(output.absolutePath))
        tts.release()
        println(
            "Generated ${audio.samples.size} samples at $sampleRate Hz -> $output"
        )
    }
}
