package com.example.luxcurve.ha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The panel's speaker: plays audio from a URL (what Home Assistant's text-to-speech returns, or a
 * file on the network) and speaks text with the Android text-to-speech engine. Requests are played
 * one after the other. The volume is the media volume of the device.
 * Call it from the [Scheduler] thread only: that is also where the callbacks arrive.
 */
class AndroidPlayer(
    context: Context,
    private val sched: Scheduler,
) : Player {

    private class Item(val url: String?, val text: String?, val volume: Int?, val onDone: (Boolean) -> Unit)

    private val appContext: Context = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val queue = ArrayDeque<Item>()
    private var current: Item? = null
    private var media: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    /** 0 not started, 1 starting, 2 ready, -1 not available on this device. */
    private var ttsState = 0
    private var utterance = 0
    private var currentUtterance: String? = null
    private var focus: AudioFocusRequest? = null
    private var restoreVolume = -1
    private var watchdog: Cancelable? = null

    override val busy: Boolean get() = current != null || queue.isNotEmpty()

    // ------------------------------------------------------------------ volume

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) * max / 100f).roundToInt().coerceIn(0, max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: SecurityException) {
            HaStatus.speaker = "Android does not allow changing the volume"
        }
    }

    // ------------------------------------------------------------------ Player

    override fun playUrl(url: String, volumePercent: Int?, onDone: (Boolean) -> Unit) {
        queue.addLast(Item(url, null, volumePercent, onDone))
        next()
    }

    override fun speak(text: String, volumePercent: Int?, onDone: (Boolean) -> Unit) {
        queue.addLast(Item(null, text, volumePercent, onDone))
        next()
    }

    override fun stop() {
        val waiting = ArrayList(queue)
        queue.clear()
        finishCurrent(false, false)
        for (item in waiting) item.onDone(false)
    }

    /** Frees the media player and the speech engine. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsState = 0
    }

    // ------------------------------------------------------------------ playing

    private fun next() {
        if (current != null) return
        val item = queue.removeFirstOrNull() ?: return
        current = item
        val volume = item.volume
        if (volume != null) {
            restoreVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            setVolumePercent(volume)
        }
        requestFocus()
        try {
            val url = item.url
            if (url != null) startMedia(item, url) else startSpeech(item, item.text ?: "")
        } catch (e: Exception) {
            HaStatus.speaker = "Playback failed: " + (e.message ?: e.javaClass.simpleName)
            finishCurrent(false, true)
        }
    }

    private fun startMedia(item: Item, url: String) {
        val mp = MediaPlayer()
        media = mp
        mp.setAudioAttributes(attrs)
        mp.setDataSource(url)
        mp.setOnPreparedListener { player ->
            if (current === item) {
                watchdog?.cancel()
                watchdog = null
                player.start()
            }
        }
        mp.setOnCompletionListener {
            if (current === item) finishCurrent(true, true)
        }
        mp.setOnErrorListener { _, what, extra ->
            if (current === item) {
                HaStatus.speaker = "Cannot play the audio (error $what/$extra)"
                finishCurrent(false, true)
            }
            true
        }
        mp.prepareAsync()
        watchdog = sched.postDelayed(PREPARE_TIMEOUT_MS) {
            if (current === item) {
                HaStatus.speaker = "The audio did not start in time"
                finishCurrent(false, true)
            }
        }
    }

    private fun startSpeech(item: Item, text: String) {
        when (ttsState) {
            2 -> speakNow(item, text)
            -1 -> {
                HaStatus.speaker = NO_TTS
                finishCurrent(false, true)
                return
            }
            else -> if (ttsState == 0) {
                ttsState = 1
                tts = TextToSpeech(appContext) { status -> sched.post { onTtsInit(status) } }
            }
        }
        watchdog = sched.postDelayed(SPEECH_TIMEOUT_MS) {
            if (current === item) {
                HaStatus.speaker = "Text to speech did not finish"
                finishCurrent(false, true)
            }
        }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsState = -1
            HaStatus.speaker = NO_TTS
            val item = current
            if (item != null && item.url == null) finishCurrent(false, true)
            return
        }
        engine.setAudioAttributes(attrs)
        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                sched.post {
                    if (utteranceId != null && utteranceId == currentUtterance) finishCurrent(true, true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                sched.post {
                    if (utteranceId != null && utteranceId == currentUtterance) {
                        HaStatus.speaker = "Text to speech failed"
                        finishCurrent(false, true)
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                sched.post {
                    if (utteranceId != null && utteranceId == currentUtterance) {
                        HaStatus.speaker = "Text to speech failed (error $errorCode)"
                        finishCurrent(false, true)
                    }
                }
            }
        })
        ttsState = 2
        val item = current
        if (item != null && item.url == null) speakNow(item, item.text ?: "")
    }

    private fun speakNow(item: Item, text: String) {
        val engine = tts ?: return
        utterance++
        val id = "luxcurve-$utterance"
        currentUtterance = id
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result != TextToSpeech.SUCCESS && current === item) {
            HaStatus.speaker = "Text to speech refused the text"
            finishCurrent(false, true)
        }
    }

    /** Ends the current item, restores the volume, reports the result and (optionally) starts the next one. */
    private fun finishCurrent(ok: Boolean, startNext: Boolean) {
        val item = current ?: return
        current = null
        watchdog?.cancel()
        watchdog = null
        releaseMedia()
        if (currentUtterance != null) {
            currentUtterance = null
            tts?.stop()
        }
        if (restoreVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0)
            } catch (e: SecurityException) {
                // see setVolumePercent
            }
            restoreVolume = -1
        }
        abandonFocus()
        if (ok) HaStatus.speaker = null
        item.onDone(ok)
        if (startNext) next()
    }

    private fun releaseMedia() {
        val mp = media ?: return
        media = null
        mp.setOnPreparedListener(null)
        mp.setOnCompletionListener(null)
        mp.setOnErrorListener(null)
        try {
            mp.stop()
        } catch (e: IllegalStateException) {
            // never started
        }
        mp.release()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        audio.abandonAudioFocusRequest(request)
    }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val SPEECH_TIMEOUT_MS = 60_000L
        private const val NO_TTS = "No text-to-speech engine on this device"
    }
}
