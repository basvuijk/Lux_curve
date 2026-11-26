package com.example.luxcurve.ha

import com.google.gson.JsonObject

/** One event of a Home Assistant Assist pipeline run (the `assist_pipeline/run` WebSocket command). */
sealed class AssistEvent {
    data class RunStart(val handlerId: Int?, val language: String?) : AssistEvent()
    object WakeWordStart : AssistEvent()
    data class WakeWordEnd(val phrase: String?) : AssistEvent()
    object SttStart : AssistEvent()
    object SttVadStart : AssistEvent()
    object SttVadEnd : AssistEvent()
    data class SttEnd(val text: String) : AssistEvent()
    object IntentStart : AssistEvent()
    data class IntentEnd(
        val speech: String?,
        val conversationId: String?,
        val continueConversation: Boolean,
    ) : AssistEvent()
    object TtsStart : AssistEvent()
    data class TtsEnd(val url: String?, val mime: String?) : AssistEvent()
    object RunEnd : AssistEvent()
    data class Error(val code: String, val message: String) : AssistEvent()
    data class Other(val type: String) : AssistEvent()
}

object AssistEvents {
    /** [event] is the "event" object of a WebSocket message: {"type": "...", "data": {...}}. */
    fun parse(event: JsonObject): AssistEvent {
        val type = event.str("type") ?: return AssistEvent.Other("")
        val data = event.obj("data")
        return when (type) {
            "run-start" -> AssistEvent.RunStart(
                data?.obj("runner_data")?.int("stt_binary_handler_id"),
                data?.str("language"),
            )
            "wake_word-start" -> AssistEvent.WakeWordStart
            "wake_word-end" -> {
                val out = data?.obj("wake_word_output")
                AssistEvent.WakeWordEnd(out?.str("wake_word_phrase") ?: out?.str("wake_word_id"))
            }
            "stt-start" -> AssistEvent.SttStart
            "stt-vad-start" -> AssistEvent.SttVadStart
            "stt-vad-end" -> AssistEvent.SttVadEnd
            "stt-end" -> AssistEvent.SttEnd(data?.obj("stt_output")?.str("text") ?: "")
            "intent-start" -> AssistEvent.IntentStart
            "intent-end" -> {
                val out = data?.obj("intent_output")
                AssistEvent.IntentEnd(
                    out?.obj("response")?.obj("speech")?.obj("plain")?.str("speech"),
                    out?.str("conversation_id"),
                    out?.bool("continue_conversation") ?: false,
                )
            }
            "tts-start" -> AssistEvent.TtsStart
            "tts-end" -> {
                // Newer versions wrap the fields in "tts_output", older ones too; accept both shapes.
                val out = data?.obj("tts_output") ?: data
                AssistEvent.TtsEnd(out?.str("url"), out?.str("mime_type"))
            }
            "run-end" -> AssistEvent.RunEnd
            "error" -> AssistEvent.Error(data?.str("code") ?: "error", data?.str("message") ?: "")
            else -> AssistEvent.Other(type)
        }
    }
}

/**
 * One `assist_pipeline/run` command.
 * [startStage] is "wake_word" or "stt" (microphone audio goes in) or "tts" (just speak [text]);
 * [endStage] is "intent" (no spoken answer) or "tts".
 */
data class AssistRequest(
    val startStage: String,
    val endStage: String,
    val text: String? = null,
    val conversationId: String? = null,
    val sampleRate: Int = Pcm.SAMPLE_RATE,
    val wakeTimeoutSec: Int = 60,
) {
    val takesAudio: Boolean get() = startStage == "wake_word" || startStage == "stt"

    fun toJson(id: Int): String {
        val o = JsonObject()
        o.addProperty("id", id)
        o.addProperty("type", "assist_pipeline/run")
        o.addProperty("start_stage", startStage)
        o.addProperty("end_stage", endStage)
        val input = JsonObject()
        if (takesAudio) {
            input.addProperty("sample_rate", sampleRate)
            if (startStage == "wake_word") input.addProperty("timeout", wakeTimeoutSec)
        } else {
            input.addProperty("text", text ?: "")
        }
        o.add("input", input)
        if (conversationId != null) o.addProperty("conversation_id", conversationId)
        return o.toString()
    }
}

/** The authenticated WebSocket connection to Home Assistant, as far as pipeline runs need it. */
interface AssistLink {
    val ready: Boolean

    /** True after a failure that retrying cannot fix (rejected token, nothing configured). */
    val failedPermanently: Boolean

    /** Connects if necessary, then calls [cb] once: ok = ready, or the reason it failed. */
    fun whenReady(timeoutMs: Long, cb: (ok: Boolean, reason: String?) -> Unit)

    fun nextId(): Int
    fun sendText(text: String)
    fun sendBinary(bytes: ByteArray)

    /** Messages with this "id" go to [handler]; a lost connection delivers a "connection_lost" error. */
    fun register(id: Int, handler: (JsonObject) -> Unit)
    fun unregister(id: Int)
}

/**
 * A single pipeline run: sends the command, frames the microphone audio the way Home Assistant wants
 * it (one handler-id byte in front of every chunk, a lone id byte as end marker) and turns the
 * events into [Listener] calls. [Listener.onFinished] is called exactly once.
 */
class AssistRun(
    private val link: AssistLink,
    private val request: AssistRequest,
    private val listener: Listener,
) {
    interface Listener {
        fun onReadyForAudio() {}
        fun onWakeWord(phrase: String?) {}
        fun onSpeechEnd() {}
        fun onTranscript(text: String) {}
        fun onReply(text: String?, conversationId: String?, continueConversation: Boolean) {}
        fun onAudio(url: String, mime: String?) {}
        fun onError(code: String, message: String) {}
        fun onFinished() {}
    }

    val id: Int = link.nextId()

    private var handlerId = -1
    private var ready = false
    private var open = false          // audio may be sent
    private var sentEnd = false
    private var endRequested = false
    private var finished = false
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0

    fun start() {
        link.register(id) { onMessage(it) }
        link.sendText(request.toJson(id))
    }

    /** Microphone audio, 16 kHz 16-bit mono. Kept (up to a few seconds) until Home Assistant is ready for it. */
    fun audio(chunk: ByteArray) {
        if (finished || sentEnd || !request.takesAudio) return
        if (ready) {
            if (open) sendFrame(chunk)
        } else {
            pending.addLast(chunk)
            pendingBytes += chunk.size
            while (pendingBytes > MAX_PENDING_BYTES && pending.isNotEmpty()) {
                pendingBytes -= pending.removeFirst().size
            }
        }
    }

    /** No more audio: tell Home Assistant to stop listening and work with what it has. */
    fun finishAudio() {
        if (finished || sentEnd) return
        if (!ready) {
            endRequested = true
            return
        }
        if (open) sendEnd()
    }

    /** Gives up on the run. Nothing more is reported to the listener. */
    fun cancel() {
        if (finished) return
        if (ready && open) sendEnd()
        finished = true
        open = false
        link.unregister(id)
    }

    private fun onMessage(obj: JsonObject) {
        if (finished) return
        when (obj.str("type")) {
            "result" -> if (obj.bool("success") == false) {
                val err = obj.obj("error")
                fail(err?.str("code") ?: "error", err?.str("message") ?: "")
            }
            "event" -> {
                val event = obj.obj("event") ?: return
                handle(AssistEvents.parse(event))
            }
        }
    }

    private fun handle(e: AssistEvent) {
        when (e) {
            is AssistEvent.RunStart -> {
                handlerId = e.handlerId ?: -1
                if (request.startStage == "wake_word") becomeReady()
            }
            is AssistEvent.SttStart -> if (request.startStage == "stt") becomeReady()
            is AssistEvent.WakeWordEnd -> listener.onWakeWord(e.phrase)
            is AssistEvent.SttVadEnd -> {
                // Home Assistant heard enough: stop sending, no end marker needed.
                open = false
                pending.clear()
                pendingBytes = 0
                listener.onSpeechEnd()
            }
            is AssistEvent.SttEnd -> {
                open = false
                listener.onTranscript(e.text)
            }
            is AssistEvent.IntentEnd -> listener.onReply(e.speech, e.conversationId, e.continueConversation)
            is AssistEvent.TtsEnd -> e.url?.let { listener.onAudio(it, e.mime) }
            is AssistEvent.RunEnd -> finish()
            is AssistEvent.Error -> fail(e.code, e.message)
            else -> {}
        }
    }

    private fun becomeReady() {
        if (ready || finished) return
        if (handlerId !in 0..255) {
            fail("protocol", "Home Assistant did not say where to send the audio")
            return
        }
        ready = true
        open = true
        while (pending.isNotEmpty()) sendFrame(pending.removeFirst())
        pendingBytes = 0
        listener.onReadyForAudio()
        if (endRequested) sendEnd()
    }

    private fun sendFrame(chunk: ByteArray) {
        val frame = ByteArray(chunk.size + 1)
        frame[0] = handlerId.toByte()
        System.arraycopy(chunk, 0, frame, 1, chunk.size)
        link.sendBinary(frame)
    }

    private fun sendEnd() {
        if (sentEnd) return
        sentEnd = true
        open = false
        link.sendBinary(byteArrayOf(handlerId.toByte()))
    }

    private fun finish() {
        if (finished) return
        finished = true
        open = false
        link.unregister(id)
        listener.onFinished()
    }

    private fun fail(code: String, message: String) {
        if (finished) return
        finished = true
        open = false
        link.unregister(id)
        listener.onError(code, message)
        listener.onFinished()
    }

    companion object {
        /** About 5 seconds of 16 kHz 16-bit audio. */
        private const val MAX_PENDING_BYTES = 160_000
    }
}
