package com.github.andreyasadchy.xtra.svagaplus

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Lightweight Engine.IO v4 + Socket.IO v4 WebSocket client over OkHttp for `svagaplus.com`.
 * Handles `join_channel`, `leave_channel`, `badge_update`, `social_rating_update`, and `badge_grants_updated`.
 */
@Singleton
class SvagaSocketClient @Inject constructor(
    baseOkHttpClient: OkHttpClient,
    private val api: SvagaPlusApi,
    private val json: Json,
) {
    interface Listener {
        fun onJoinAck(channel: String, payload: JsonObject?)
        fun onBadgeUpdate(channel: String, payload: JsonObject)
        fun onSocialRatingUpdate(payload: JsonObject)
        fun onBadgeGrantsUpdated(payload: JsonObject)
    }

    private val wsClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val ackCounter = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var socketIoConnected = false
    private var manualDisconnect = false
    private var failedAttempts = 0
    private var reconnectJob: Job? = null
    private var desiredChannel: String? = null
    private var joinedChannel: String? = null
    private val pendingAcks = LinkedHashMap<Int, (JsonElement?) -> Unit>()

    @Volatile
    var listener: Listener? = null

    fun joinChannel(channelLogin: String) {
        val normalized = channelLogin.trim().lowercase()
        if (!LOGIN_REGEX.matches(normalized)) return

        synchronized(lock) {
            val previous = joinedChannel
            desiredChannel = normalized
            manualDisconnect = false
            if (socketIoConnected && webSocket != null) {
                if (previous != null && previous != normalized) {
                    sendEventInternal("leave_channel", buildJsonObject { put("channel_name", previous) })
                }
                if (joinedChannel != normalized) {
                    emitJoinCurrentChannelLocked()
                }
                return
            }
            if (webSocket == null) {
                connectLocked()
            }
        }
    }

    fun leaveChannel(channelLogin: String? = null) {
        synchronized(lock) {
            val target = channelLogin?.trim()?.lowercase()
            if (target != null && desiredChannel != null && desiredChannel != target) return
            val prev = joinedChannel
            desiredChannel = null
            joinedChannel = null
            reconnectJob?.cancel()
            reconnectJob = null
            if (socketIoConnected && prev != null) {
                sendEventInternal("leave_channel", buildJsonObject { put("channel_name", prev) })
            }
            manualDisconnect = true
            socketIoConnected = false
            pendingAcks.clear()
            webSocket?.close(1000, "leave")
            webSocket = null
        }
    }

    private fun connectLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        val wsUrl = buildSocketIoWsUrl(api.getBackendUrl())
        val request = Request.Builder().url(wsUrl).build()
        socketIoConnected = false
        webSocket = wsClient.newWebSocket(request, SocketListener())
    }

    private fun emitJoinCurrentChannelLocked() {
        val channel = desiredChannel ?: return
        joinedChannel = channel
        val ackId = ackCounter.getAndIncrement()
        pendingAcks[ackId] = { ackElement ->
            listener?.onJoinAck(channel, ackElement as? JsonObject)
        }
        val payload = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("join_channel"))
            add(buildJsonObject { put("channel_name", channel) })
        }
        webSocket?.send("42$ackId$payload")
    }

    private fun sendEventInternal(event: String, data: JsonObject) {
        val payload = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(event))
            add(data)
        }
        webSocket?.send("42$payload")
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (manualDisconnect || desiredChannel == null) return
            socketIoConnected = false
            joinedChannel = null
            webSocket = null
            pendingAcks.clear()
            failedAttempts += 1
            val delayMs = reconnectDelayMs(failedAttempts, Random.nextDouble())
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    if (!manualDisconnect && desiredChannel != null && webSocket == null) {
                        connectLocked()
                    }
                }
            }
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.isEmpty()) return
            try {
                when (text[0]) {
                    '0' -> {
                        // Engine.IO OPEN -> send Socket.IO CONNECT ("40")
                        webSocket.send("40")
                    }
                    '2' -> {
                        // Engine.IO PING -> reply PONG ("3")
                        webSocket.send("3${text.substring(1)}")
                    }
                    '4' -> handleSocketIoPacket(webSocket, text.substring(1))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed handling Socket.IO frame", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val shouldReconnect = synchronized(lock) {
                if (this@SvagaSocketClient.webSocket !== webSocket) false
                else !manualDisconnect && desiredChannel != null
            }
            if (shouldReconnect) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val shouldReconnect = synchronized(lock) {
                if (this@SvagaSocketClient.webSocket !== webSocket) false
                else !manualDisconnect && desiredChannel != null
            }
            if (shouldReconnect) scheduleReconnect()
        }
    }

    private fun handleSocketIoPacket(ws: WebSocket, packet: String) {
        if (packet.isEmpty()) return
        when (packet[0]) {
            '0' -> {
                // Socket.IO CONNECTED
                synchronized(lock) {
                    if (webSocket !== ws) return
                    socketIoConnected = true
                    failedAttempts = 0
                    emitJoinCurrentChannelLocked()
                }
            }
            '1' -> {
                // Server-initiated Socket.IO DISCONNECT
                scheduleReconnect()
            }
            '2' -> {
                // Socket.IO EVENT: optional ack digits followed by JSON array ["event_name", payload]
                val bracketIdx = packet.indexOf('[')
                if (bracketIdx < 1) return
                val arr = json.parseToJsonElement(packet.substring(bracketIdx)) as? JsonArray ?: return
                if (arr.isEmpty()) return
                val eventName = arr[0].jsonPrimitive.contentOrNull ?: return
                val argObj = arr.getOrNull(1) as? JsonObject
                val activeChannel = synchronized(lock) { joinedChannel ?: desiredChannel }
                when (eventName) {
                    "badge_update" -> if (activeChannel != null && argObj != null) {
                        listener?.onBadgeUpdate(activeChannel, argObj)
                    }
                    "social_rating_update" -> if (argObj != null) {
                        listener?.onSocialRatingUpdate(argObj)
                    }
                    "badge_grants_updated" -> if (argObj != null) {
                        listener?.onBadgeGrantsUpdated(argObj)
                    }
                }
            }
            '3' -> {
                // Socket.IO ACK: 3<id>[payload]
                val bracketIdx = packet.indexOf('[')
                if (bracketIdx <= 1) return
                val ackId = packet.substring(1, bracketIdx).toIntOrNull() ?: return
                val callback = synchronized(lock) { pendingAcks.remove(ackId) } ?: return
                val arr = json.parseToJsonElement(packet.substring(bracketIdx)) as? JsonArray
                callback(arr?.firstOrNull())
            }
        }
    }

    companion object {
        private const val TAG = "SvagaSocketClient"
        private const val BASE_DELAY_MS = 1000.0
        private const val MAX_DELAY_MS = 15000.0
        private const val JITTER = 0.7
        private val LOGIN_REGEX = Regex("""^[a-z0-9_]{3,25}$""")

        fun buildSocketIoWsUrl(backendHttpUrl: String): String {
            val clean = backendHttpUrl.trim().trimEnd('/')
            val wsBase = when {
                clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring(8)
                clean.startsWith("http://", ignoreCase = true) -> "ws://" + clean.substring(7)
                else -> "wss://$clean"
            }
            return "$wsBase/socket.io/?EIO=4&transport=websocket"
        }

        fun reconnectDelayMs(attempt: Int, randomZeroToOne: Double): Long {
            val base = min(BASE_DELAY_MS * 2.0.pow((attempt - 1).coerceAtLeast(0)), MAX_DELAY_MS)
            return (base * (1.0 + JITTER * (2.0 * randomZeroToOne - 1.0))).roundToLong().coerceAtLeast(250L)
        }
    }
}
