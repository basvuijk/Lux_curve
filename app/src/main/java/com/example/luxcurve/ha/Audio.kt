package com.example.luxcurve.ha

/** The panel's speaker. Callbacks arrive on the scheduler thread. */
interface Player {
    /** Something is playing or queued; the microphone is ignored meanwhile so the panel does not hear itself. */
    val busy: Boolean

    /** [onDone] gets true when playback finished normally and false when it failed or was stopped. */
    fun playUrl(url: String, volumePercent: Int?, onDone: (Boolean) -> Unit)

    /** Speaks with the on-device text-to-speech engine. */
    fun speak(text: String, volumePercent: Int?, onDone: (Boolean) -> Unit)

    fun stop()
}

/** The panel's microphone: 16 kHz, 16-bit, mono PCM in chunks of a few tens of milliseconds. */
interface MicSource {
    interface Listener {
        fun onChunk(pcm: ByteArray)
        fun onProblem(message: String)
    }

    /** Starts capturing. Returns null when it runs, or the reason the microphone could not be opened. */
    fun start(listener: Listener): String?
    fun stop()
}
