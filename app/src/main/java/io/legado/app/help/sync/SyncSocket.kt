package io.legado.app.help.sync

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private data class SyncSocketMessage(
    @SerializedName("type") val type: String = "",
    @SerializedName("source") val source: String = "",
)

/**
 * 同步变更通知：收到服务端广播后立即增量拉取。
 * HTTP 轮询仍然保留, WS 断开时自动重连。
 */
object SyncSocket {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        okHttpClient.newBuilder().apply {
            // 基础 client 的拦截器会改写响应, 导致 WebSocket 升级失败
            interceptors().clear()
            networkInterceptors().clear()
            callTimeout(0, TimeUnit.MILLISECONDS)
            connectTimeout(20, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
            writeTimeout(0, TimeUnit.MILLISECONDS)
            pingInterval(30, TimeUnit.SECONDS)
        }.build()
    }

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var retryDelay = 2_000L

    private var retryJob: Job? = null


    @Synchronized
    fun ensureStarted() {
        if (!ServerSync.isConfigured) return
        if (webSocket != null || connecting) return
        retryJob?.cancel()
        connecting = true
        val request = runCatching {
            Request.Builder()
                .url(BackupServer.wsUrl)
                .header("X-User-Phone", BackupServer.phone)
                .build()
        }.getOrElse {
            connecting = false
            return
        }
        runCatching { client.newWebSocket(request, listener) }
            .onFailure {
                connecting = false
                scheduleRetry()
            }
    }

    @Synchronized
    fun restart() {
        runCatching { webSocket?.close(1000, "restart") }
        webSocket = null
        connecting = false
        retryDelay = 2_000L
        ensureStarted()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
            ensureStarted()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@SyncSocket.webSocket = webSocket
            connecting = false
            retryDelay = 2_000L
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = GSON.fromJsonObject<SyncSocketMessage>(text).getOrNull() ?: return
            if (message.type != "sync") return
            if (message.source.isNotBlank() && message.source == AppConst.androidId) return
            SyncManager.requestPull(force = true)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@SyncSocket.webSocket = null
            connecting = false
            scheduleRetry()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.putDebug("同步通知连接断开: ${t.localizedMessage}")
            this@SyncSocket.webSocket = null
            connecting = false
            scheduleRetry()
        }
    }
}
