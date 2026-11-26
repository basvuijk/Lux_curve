package com.example.luxcurve.ha

import kotlin.math.min

/** Receives what the voice engine is doing so it can be published and shown. */
interface VoiceOutput {
    fun state(state: String)

    /** A short note for the settings screen: the last problem, or null when all is well. */
    fun detail(text: String?)

    fun command(text: String)
    fun response(text: String)
}

/**
 * Voice commands through the Home Assistant Assist pipeline.
 *
 * Push to talk: [listen] (the "Listen" button in Home Assistant) or somebody walking up to the panel
 * opens the microphone for one command. Wake word: the microphone streams to Home Assistant, which
 * listens for the wake word itself, and every detection starts a command.
 * The spoken answer is played through [Player]; when Home Assistant asks a question it listens again.
 *
 * Public functions may be called from any thread, the work happens on the [Scheduler] thread.
 */
class VoiceEngine(
    private val sched: Scheduler,
    private val link: AssistLink,
    private val mic: MicSource,
    private val player: Player,
    private val out: VoiceOutput,
    initial: HaConfig,
) {
    private enum class Kind { WAKE, TALK }

    private var cfg = initial
    private var started = false
    private var closed = false

    private var state = ""
    private var detail: String? = null

    /** A session (waiting for the wake word, or one command with its answer) is set up or running. */
    private var active = false
    private var kind = Kind.TALK
    private var run: AssistRun? = null
    private var gen = 0
    private var speaking = false

    private var micOn = false
    private var silentBytes = 0
    private var silentWarned = false

    private var followUps = 0
    private var retryMs = RETRY_MIN_MS
    private var retryTimer: Cancelable? = null
    private var listenTimer: Cancelable? = null
    private var hardTimer: Cancelable? = null

    private var near = false
    private var lastApproachAt = Long.MIN_VALUE

    private val micListener = object : MicSource.Listener {
        override fun onChunk(pcm: ByteArray) {
            sched.post { onMicChunk(pcm) }
        }

        override fun onProblem(message: String) {
            sched.post { onMicProblem(message) }
        }
    }

    // ------------------------------------------------------------------ public API

    fun start() {
        sched.post {
            if (!closed && !started) {
                started = true
                reconcile()
            }
        }
    }

    fun updateConfig(new: HaConfig) {
        sched.post {
            if (closed) return@post
            val old = cfg
            cfg = new
            if (!started) return@post
            val restart = old.voiceReady != new.voiceReady || old.voiceWake != new.voiceWake ||
                old.voiceSpeak != new.voiceSpeak || !old.sameHa(new)
            if (restart) {
                resetSession()
                retryMs = RETRY_MIN_MS
                reconcile()
            }
        }
    }

    /** The "Listen" button: one voice command now, whatever the mode. */
    fun listen() {
        sched.post {
            if (closed || !started) return@post
            if (!cfg.voiceReady) {
                setDetail(notReadyReason() ?: "Voice commands are switched off")
                return@post
            }
            // Our own reply is stopped by beginSession; an announcement that is playing is stopped here.
            val foreign = player.busy && !speaking
            followUps = 0
            beginSession(Kind.TALK, null)
            if (foreign) player.stop()
        }
    }

    /** The "Stop audio" button: silences our own reply and goes back to idle. */
    fun stopSpeaking() {
        sched.post {
            if (closed || !started || !speaking) return@post
            followUps = 0
            endSession()
            reconcile()
        }
    }

    /** The presence state. Walking up to the panel (far to near) can start a voice command. */
    fun onPresence(isNear: Boolean) {
        sched.post {
            val was = near
            near = isNear
            if (closed || !started || !isNear || was) return@post
            if (!cfg.voiceReady || !cfg.voiceApproach) return@post
            if (talking() || speaking || player.busy) return@post
            val t = sched.now()
            if (lastApproachAt != Long.MIN_VALUE && t - lastApproachAt < APPROACH_COOLDOWN_MS) return@post
            lastApproachAt = t
            followUps = 0
            beginSession(Kind.TALK, null)
        }
    }

    fun stop() {
        sched.post {
            if (closed) return@post
            closed = true
            resetSession()
            setState(VoiceState.DISABLED)
        }
    }

    // ------------------------------------------------------------------ session handling

    private fun talking(): Boolean =
        active && (kind == Kind.TALK || state == VoiceState.LISTENING || state == VoiceState.PROCESSING)

    private fun notReadyReason(): String? = when {
        !cfg.voiceEnabled -> null
        !cfg.haConfigured -> "Enter the Home Assistant address and access token"
        !cfg.voiceActive -> "Voice listening is switched off"
        else -> null
    }

    /** Brings the engine in line with the configuration: off, idle, or waiting for the wake word. */
    private fun reconcile() {
        if (closed || !started) return
        if (!cfg.voiceReady) {
            resetSession()
            setState(VoiceState.DISABLED)
            setDetail(notReadyReason())
            return
        }
        if (active || retryTimer != null) return
        if (cfg.voiceWake) {
            beginSession(Kind.WAKE, null)
        } else {
            stopMic()
            setState(VoiceState.IDLE)
        }
    }

    /** Cancels everything that is running and releases the microphone. */
    private fun resetSession() {
        endSession()
        stopMic()
        retryTimer?.cancel()
        retryTimer = null
    }

    /** Cancels the current run, its timers and the reply that is playing. The microphone is left alone. */
    private fun endSession() {
        gen++
        active = false
        listenTimer?.cancel()
        listenTimer = null
        hardTimer?.cancel()
        hardTimer = null
        val r = run
        run = null
        r?.cancel()
        if (speaking) {
            speaking = false
            player.stop()
        }
    }

    private fun beginSession(k: Kind, conversation: String?) {
        retryTimer?.cancel()
        retryTimer = null
        endSession()
        active = true
        kind = k
        if (k == Kind.TALK) setState(VoiceState.LISTENING)
        val g = gen
        link.whenReady(CONNECT_TIMEOUT_MS) { ok, reason ->
            if (g != gen || closed) return@whenReady
            if (ok) {
                startRun(k, conversation, g)
            } else {
                fail(reason ?: "Cannot reach Home Assistant", link.failedPermanently)
            }
        }
    }

    private fun startRun(k: Kind, conversation: String?, g: Int) {
        val end = if (cfg.voiceSpeak) "tts" else "intent"
        val request = if (k == Kind.WAKE) {
            AssistRequest(startStage = "wake_word", endStage = end)
        } else {
            AssistRequest(startStage = "stt", endStage = end, conversationId = conversation)
        }
        val r = AssistRun(link, request, Session(g, k))
        run = r
        r.start()
        if (g != gen) return // the run failed right away
        if (!micOn) {
            val problem = mic.start(micListener)
            if (problem != null) {
                fail("Microphone: $problem", false)
                return
            }
            micOn = true
            silentBytes = 0
            silentWarned = false
        }
        if (k == Kind.TALK) armTalkTimers(g)
    }

    /** A command must not hang for ever: ask Home Assistant to wrap up, and give up if it does not answer. */
    private fun armTalkTimers(g: Int) {
        listenTimer?.cancel()
        hardTimer?.cancel()
        listenTimer = sched.postDelayed(LISTEN_MAX_MS) {
            if (g == gen) run?.finishAudio()
        }
        hardTimer = sched.postDelayed(RUN_MAX_MS) {
            if (g == gen) fail("Home Assistant did not answer in time", false)
        }
    }

    private fun finished(s: Session) {
        listenTimer?.cancel()
        listenTimer = null
        hardTimer?.cancel()
        hardTimer = null
        run = null
        if (s.runKind == Kind.TALK) stopMic()
        val code = s.errorCode
        if (code != null) {
            onRunError(s, code)
            return
        }
        retryMs = RETRY_MIN_MS
        clearProblem()
        val text = s.replyText?.takeIf { it.isNotBlank() }
        val url = s.audioUrl
        if (cfg.voiceSpeak && (url != null || text != null)) {
            say(url, text, s)
        } else {
            afterReply(s)
        }
    }

    private fun onRunError(s: Session, code: String) {
        when (code) {
            // Nothing wrong: the wait for the wake word ran out, or another device took the wake-up.
            "wake-word-timeout", "duplicate_wake_up_detected" -> {
                active = false
                reconcile()
            }
            "stt-no-text-recognized" -> {
                setDetail("No speech recognised")
                active = false
                reconcile()
            }
            else -> {
                val message = s.errorMessage
                fail(if (message.isBlank()) "Home Assistant error: $code" else message, false)
            }
        }
    }

    /** Plays the answer: Home Assistant's own voice when it sent audio, the on-device voice otherwise. */
    private fun say(url: String?, text: String?, s: Session) {
        val g = gen
        speaking = true
        setState(VoiceState.SPEAKING)
        val done: () -> Unit = {
            if (g == gen) {
                speaking = false
                afterReply(s)
            }
        }
        if (url != null) {
            player.playUrl(cfg.resolveUrl(url), null) { ok ->
                if (g == gen) {
                    if (!ok && text != null) {
                        player.speak(text, null) { done() }
                    } else {
                        done()
                    }
                }
            }
        } else if (text != null) {
            player.speak(text, null) { done() }
        }
    }

    private fun afterReply(s: Session) {
        if (s.continueConversation && followUps < MAX_FOLLOW_UPS && cfg.voiceReady) {
            followUps++
            beginSession(Kind.TALK, s.conversationId)
            return
        }
        followUps = 0
        active = false
        reconcile()
    }

    private fun fail(message: String, permanent: Boolean) {
        resetSession()
        setState(VoiceState.ERROR)
        setDetail(message)
        if (permanent) return // nothing to retry: the settings have to change
        val delay = retryMs
        retryMs = min(retryMs * 2, RETRY_MAX_MS)
        retryTimer = sched.postDelayed(delay) {
            retryTimer = null
            reconcile()
        }
    }

    // ------------------------------------------------------------------ microphone

    private fun stopMic() {
        if (!micOn) return
        micOn = false
        mic.stop()
    }

    private fun onMicChunk(pcm: ByteArray) {
        if (closed || !micOn) return
        trackSilence(pcm)
        val r = run ?: return
        if (player.busy) return // do not listen to our own speaker
        Pcm.applyGain(pcm, pcm.size, cfg.micGain)
        r.audio(pcm)
    }

    private fun onMicProblem(message: String) {
        if (closed || !micOn) return
        fail("Microphone: $message", false)
    }

    /** Android hands out all-zero audio instead of an error when it blocks the microphone. */
    private fun trackSilence(pcm: ByteArray) {
        if (Pcm.isSilent(pcm, pcm.size)) {
            silentBytes += pcm.size
            if (!silentWarned && silentBytes >= SILENT_WARN_BYTES) {
                silentWarned = true
                setDetail(SILENT_MESSAGE)
            }
        } else {
            silentBytes = 0
            if (silentWarned) {
                silentWarned = false
                if (detail == SILENT_MESSAGE) setDetail(null)
            }
        }
    }

    // ------------------------------------------------------------------ reporting

    private fun setState(s: String) {
        if (s == state) return
        state = s
        out.state(s)
    }

    private fun setDetail(d: String?) {
        if (d == detail) return
        detail = d
        out.detail(d)
    }

    /** A run worked, so an older problem is history. The silent microphone warning stays until audio returns. */
    private fun clearProblem() {
        if (detail != SILENT_MESSAGE) setDetail(null)
    }

    /** One run of the Assist pipeline, and what it produced so far. */
    private inner class Session(val g: Int, val runKind: Kind) : AssistRun.Listener {
        var replyText: String? = null
        var audioUrl: String? = null
        var conversationId: String? = null
        var continueConversation = false
        var errorCode: String? = null
        var errorMessage = ""

        private fun current() = g == gen && !closed

        override fun onReadyForAudio() {
            if (!current()) return
            if (runKind == Kind.WAKE) {
                setState(VoiceState.WAKE_WORD)
                clearProblem()
            } else {
                setState(VoiceState.LISTENING)
            }
        }

        override fun onWakeWord(phrase: String?) {
            if (!current()) return
            setState(VoiceState.LISTENING)
            armTalkTimers(g)
        }

        override fun onSpeechEnd() {
            if (!current()) return
            setState(VoiceState.PROCESSING)
            if (runKind == Kind.TALK) stopMic()
        }

        override fun onTranscript(text: String) {
            if (!current()) return
            setState(VoiceState.PROCESSING)
            if (runKind == Kind.TALK) stopMic()
            if (text.isNotBlank()) out.command(text)
        }

        override fun onReply(text: String?, conversationId: String?, continueConversation: Boolean) {
            if (!current()) return
            replyText = text
            this.conversationId = conversationId
            this.continueConversation = continueConversation
            if (!text.isNullOrBlank()) out.response(text)
        }

        override fun onAudio(url: String, mime: String?) {
            if (current()) audioUrl = url
        }

        override fun onError(code: String, message: String) {
            if (!current()) return
            errorCode = code
            errorMessage = message
        }

        override fun onFinished() {
            if (current()) finished(this)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val LISTEN_MAX_MS = 20_000L
        private const val RUN_MAX_MS = 60_000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val APPROACH_COOLDOWN_MS = 10_000L
        private const val MAX_FOLLOW_UPS = 3

        /** Three seconds of 16 kHz 16-bit mono audio. */
        private const val SILENT_WARN_BYTES = 96_000

        const val SILENT_MESSAGE = "The microphone only delivers silence (muted, or blocked by Android)"
    }
}
