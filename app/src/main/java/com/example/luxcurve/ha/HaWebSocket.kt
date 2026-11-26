package com.example.luxcurve.ha

import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The authenticated Home Assistant WebSocket (`/api/websocket`, long-lived access token).
 * Opened when something needs it and kept open; it is not reopened by itself after it dropped.
 * Everything here runs on the [Scheduler] thread; OkHttp's callbacks are posted to it.
 */
class HaWebSocket(
    private val sched: Scheduler,
    private val config: () -> HaConfig,
) : AssistLink {

    private class Waiter(val cb: (Boolean, String?) -> Unit) {
        var timer: Cancelable? = null
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Identifies the current socket; events from an older one are ignored. */
    private var epoch = 0
    private var authed = false
    private var connecting = false
    private var permanent: String? = null
    private var ids = 1
    private val handlers = HashMap<Int, (JsonObject) -> Unit>()
    private val waiters = ArrayList<Waiter>()

    override val ready: Boolean get() = authed && socket != null

    override val failedPermanently: Boolean get() = permanent != null

    override fun whenReady(timeoutMs: Long, cb: (Boolean, String?) -> Unit) {
        if (ready) {
            cb(true, null)
            return
        }
        val perm = permanent
        if (perm != null) {
            cb(false, perm)
            return
        }
        val w = Waiter(cb)
        waiters.add(w)
        w.timer = sched.postDelayed(timeoutMs) {
            if (waiters.remove(w)) cb(false, "Home Assistant did not answer in time")
        }
        connect()
    }

    override fun nextId(): Int = ids++

    override fun sendText(text: String) {
        socket?.send(text)
    }

    override fun sendBinary(bytes: ByteArray) {
        socket?.send(bytes.toByteString())
    }

    override fun register(id: Int, handler: (JsonObject) -> Unit) {
        handlers[id] = handler
    }

    override fun unregister(id: Int) {
        handlers.remove(id)
    }

    /** The address or token changed: drop the connection and forget a rejected token. */
    fun reset() {
        permanent = null
        drop("Home Assistant settings changed")
    }

    /** Final close, when the service ends. */
    fun close() {
        drop("Closed")
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun connect() {
        if (connecting || ready) return
        val cfg = config()
        val url = cfg.wsUrl()
        if (url == null || cfg.haToken.isBlank()) {
            fail("Enter the Home Assistant address and access token", true)
            return
        }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            fail("The Home Assistant address is not valid", true)
            return
        }
        connecting = true
        val mine = ++epoch
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                sched.post { onText(mine, text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sched.post { onGone(mine, "Connection to Home Assistant closed") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = describe(t, response)
                sched.post { onGone(mine, reason) }
            }
        })
    }

    private fun onText(mine: Int, text: String) {
        if (mine != epoch) return
        val obj = parseObject(text) ?: return
        when (obj.str("type")) {
            "auth_required" -> {
                val auth = JsonObject()
                auth.addProperty("type", "auth")
                auth.addProperty("access_token", config().haToken)
                socket?.send(auth.toString())
            }
            "auth_ok" -> {
                authed = true
                connecting = false
                val list = ArrayList(waiters)
                waiters.clear()
                for (w in list) {
                    w.timer?.cancel()
                    w.cb(true, null)
                }
            }
            "auth_invalid" -> fail("Home Assistant rejected the access token", true)
            else -> {
                val id = obj.int("id") ?: return
                handlers[id]?.invoke(obj)
            }
        }
    }

    private fun onGone(mine: Int, reason: String) {
        if (mine != epoch) return
        drop(reason)
    }

    /** Closes the socket (if any), fails everybody who waits for it and tells the running pipelines. */
    private fun drop(reason: String) {
        epoch++
        val s = socket
        socket = null
        authed = false
        connecting = false
        if (s != null) {
            try {
                s.close(1000, null)
            } catch (e: Exception) {
                // already closed
            }
        }
        failWaiters(reason)
        val list = ArrayList(handlers.entries)
        handlers.clear()
        for (entry in list) {
            val lost = JsonObject()
            lost.addProperty("id", entry.key)
            lost.addProperty("type", "result")
            lost.addProperty("success", false)
            val err = JsonObject()
            err.addProperty("code", "connection_lost")
            err.addProperty("message", reason)
            lost.add("error", err)
            entry.value(lost)
        }
    }

    private fun fail(reason: String, forever: Boolean) {
        if (forever) permanent = reason
        val s = socket
        if (s != null) {
            epoch++
            socket = null
            try {
                s.close(1000, null)
            } catch (e: Exception) {
                // already closed
            }
        }
        authed = false
        connecting = false
        failWaiters(reason)
    }

    private fun failWaiters(reason: String) {
        val list = ArrayList(waiters)
        waiters.clear()
        for (w in list) {
            w.timer?.cancel()
            w.cb(false, reason)
        }
    }

    private fun describe(t: Throwable, response: Response?): String {
        if (response != null) return "Home Assistant answered HTTP " + response.code + ", check the address"
        return when (t) {
            is UnknownHostException -> "Cannot find Home Assistant, check the address"
            is ConnectException -> "Cannot connect to Home Assistant, check the address and port"
            is SocketTimeoutException -> "Home Assistant did not answer in time"
            else -> t.message ?: t.javaClass.simpleName
        }
    }
}
