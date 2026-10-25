package io.legado.app.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.help.tts.parseTtsSelection
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.NeuralTtsReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

internal fun resolveReadAloudEngineName(
    ttsEngine: String?,
    systemTtsName: String,
): String {
    val engine = ttsEngine?.takeIf { it.isNotBlank() } ?: return systemTtsName
    return parseTtsSelection(engine)?.title?.takeIf { it.isNotBlank() } ?: systemTtsName
}

object ReadAloud {
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine

    val isNeuralTtsSelected: Boolean
        get() = parseTtsSelection(ttsEngine)?.let {
            RemoteTtsEngines.isLocalCode(it.code)
        } == true
    private var aloudClass: Class<*> = getReadAloudClass()

    val followReadAloudPosition: Boolean
        get() = BaseReadAloudService.followReadAloudPosition

    val readAloudChapterIndex: Int
        get() = BaseReadAloudService.readAloudChapterIndex

    val readAloudChapterStart: Int
        get() = BaseReadAloudService.readAloudChapterStart

    fun detachReadAloudFollow() {
        BaseReadAloudService.detachReadAloudFollow()
    }

    fun restoreReadAloudFollow() {
        BaseReadAloudService.restoreReadAloudFollow()
    }

    private fun getReadAloudClass(): Class<*> {
        val ttsEngine = ttsEngine
        if (ttsEngine.isNullOrBlank()) {
            return TTSReadAloudService::class.java
        }
        val engineValue = parseTtsSelection(ttsEngine)?.code
        if (engineValue != null && RemoteTtsEngines.isLocalCode(engineValue)) {
            return NeuralTtsReadAloudService::class.java
        }
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        stop(appCtx)
        aloudClass = getReadAloudClass()
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0,
        rewindToSentenceStart: Boolean = false
    ) {
        if (!BaseReadAloudService.isRun) {
            restoreReadAloudFollow()
        }
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.play
        intent.putExtra("play", play)
        intent.putExtra("pageIndex", pageIndex)
        intent.putExtra("startPos", startPos)
        intent.putExtra("rewindToSentenceStart", rewindToSentenceStart)
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun playByEventBus(
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val bundle = Bundle().apply {
            putBoolean("play", play)
            putInt("pageIndex", pageIndex)
            putInt("startPos", startPos)
        }
        postEvent(EventBus.READ_ALOUD_PLAY, bundle)
    }

    fun pause(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.pause
            context.startForegroundServiceCompat(intent)
        }
    }

    fun resume(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.resume
            context.startForegroundServiceCompat(intent)
        }
    }

    fun stop(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.stop
            context.startForegroundServiceCompat(intent)
        }
    }

    fun prevParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prevParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.nextParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun prevChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prev
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.next
            context.startForegroundServiceCompat(intent)
        }
    }

    fun upTtsSpeechRate(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.upTtsSpeechRate
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setTimer(context: Context, minute: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setChapterStop(context: Context, count: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setChapterStop
            intent.putExtra("count", count)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun getEngineName(context: Context): String {
        val systemTtsName = context.getString(R.string.system_tts)
        return resolveReadAloudEngineName(ttsEngine, systemTtsName)
    }

}
