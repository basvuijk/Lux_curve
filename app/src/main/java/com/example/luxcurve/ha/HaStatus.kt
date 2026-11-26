package com.example.luxcurve.ha

/** What the service is doing, for the settings screen (same process, so plain fields are enough). */
object HaStatus {
    @Volatile var running = false
    @Volatile var mqtt: String = "Off"
    @Volatile var voice: String = "Off"

    /** Last problem or note about the voice commands, or null. */
    @Volatile var voiceDetail: String? = null
    @Volatile var lastCommand: String? = null
    @Volatile var lastReply: String? = null

    /** Last problem with the speaker, or null. */
    @Volatile var speaker: String? = null

    /** The foreground service was allowed to start with microphone access. */
    @Volatile var micService = false
}
