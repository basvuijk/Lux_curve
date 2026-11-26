package com.example.luxcurve.ha

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Virtual clock: nothing runs until [advance] or [idle] is called. */
class FakeScheduler : Scheduler {
    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    var t = 0L
    private val tasks = ArrayList<Task>()
    private var seq = 0L

    override fun post(block: () -> Unit) {
        postDelayed(0, block)
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): Cancelable {
        val task = Task(t + delayMs, seq++, block)
        tasks.add(task)
        return Cancelable { task.cancelled = true }
    }

    override fun now(): Long = t

    fun advance(ms: Long) {
        val end = t + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }
                .minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            if (next.at > t) t = next.at
            next.block()
        }
        t = end
    }

    fun idle() = advance(0)

    fun pendingCount() = tasks.count { !it.cancelled }
}

class FakeMqtt : MqttLink {
    var listener: MqttLink.Listener? = null
    val published = ArrayList<Triple<String, String, Boolean>>()
    val subscribed = ArrayList<String>()
    var stopped = false

    override fun start(listener: MqttLink.Listener) {
        this.listener = listener
    }

    override fun publish(topic: String, payload: String, retain: Boolean) {
        published.add(Triple(topic, payload, retain))
    }

    override fun subscribe(topic: String) {
        subscribed.add(topic)
    }

    override fun stop() {
        stopped = true
    }

    fun last(topic: String): String? = published.lastOrNull { it.first == topic }?.second
}

class RecordingActions : HaBridge.Actions {
    val log = ArrayList<String>()
    override fun setVolume(percent: Int) {
        log.add("volume:$percent")
    }

    override fun announce(cmd: AnnounceCmd) {
        log.add("announce:" + cmd.message + "|" + cmd.url + "|" + cmd.volume)
    }

    override fun stopAudio() {
        log.add("stop")
    }

    override fun listen() {
        log.add("listen")
    }

    override fun setVoiceListening(on: Boolean) {
        log.add("voice:$on")
    }
}

class FakeLink : AssistLink {
    override var ready = true
    override var failedPermanently = false
    var failReason: String? = null
    var connectCalls = 0
    private var ids = 1
    val sentText = ArrayList<String>()
    val sentBinary = ArrayList<ByteArray>()
    val handlers = HashMap<Int, (JsonObject) -> Unit>()

    override fun whenReady(timeoutMs: Long, cb: (Boolean, String?) -> Unit) {
        connectCalls++
        val reason = failReason
        if (reason == null) cb(true, null) else cb(false, reason)
    }

    override fun nextId(): Int = ids++

    override fun sendText(text: String) {
        sentText.add(text)
    }

    override fun sendBinary(bytes: ByteArray) {
        sentBinary.add(bytes)
    }

    override fun register(id: Int, handler: (JsonObject) -> Unit) {
        handlers[id] = handler
    }

    override fun unregister(id: Int) {
        handlers.remove(id)
    }

    fun server(id: Int, json: String) {
        handlers[id]?.invoke(JsonParser.parseString(json).asJsonObject)
    }

    fun event(id: Int, type: String, data: String = "{}") =
        server(id, """{"id":$id,"type":"event","event":{"type":"$type","data":$data}}""")

    fun runStart(id: Int, handler: Int = 1) =
        event(id, "run-start", """{"language":"en","runner_data":{"stt_binary_handler_id":$handler,"timeout":300}}""")
}

class FakePlayer : Player {
    override var busy = false
    val log = ArrayList<String>()
    var urlResult: Boolean? = true      // null: stays busy until finish() is called
    var speakResult: Boolean? = true
    private var pending: ((Boolean) -> Unit)? = null

    override fun playUrl(url: String, volumePercent: Int?, onDone: (Boolean) -> Unit) {
        log.add("url:$url:$volumePercent")
        begin(urlResult, onDone)
    }

    override fun speak(text: String, volumePercent: Int?, onDone: (Boolean) -> Unit) {
        log.add("speak:$text:$volumePercent")
        begin(speakResult, onDone)
    }

    private fun begin(result: Boolean?, onDone: (Boolean) -> Unit) {
        if (result == null) {
            busy = true
            pending = onDone
        } else {
            busy = false
            onDone(result)
        }
    }

    override fun stop() {
        log.add("stop")
        val p = pending
        pending = null
        busy = false
        p?.invoke(false)
    }

    fun finish(ok: Boolean = true) {
        val p = pending
        pending = null
        busy = false
        p?.invoke(ok)
    }
}

class FakeMic : MicSource {
    var problem: String? = null
    var running = false
    var startCount = 0
    var stopCount = 0
    var listener: MicSource.Listener? = null

    override fun start(listener: MicSource.Listener): String? {
        startCount++
        val p = problem
        if (p != null) return p
        running = true
        this.listener = listener
        return null
    }

    override fun stop() {
        running = false
        stopCount++
    }

    fun chunk(bytes: ByteArray) {
        listener?.onChunk(bytes)
    }
}

class FakeOut : VoiceOutput {
    val states = ArrayList<String>()
    var detail: String? = null
    val commands = ArrayList<String>()
    val responses = ArrayList<String>()

    override fun state(state: String) {
        states.add(state)
    }

    override fun detail(text: String?) {
        detail = text
    }

    override fun command(text: String) {
        commands.add(text)
    }

    override fun response(text: String) {
        responses.add(text)
    }

    val last: String? get() = states.lastOrNull()
}
