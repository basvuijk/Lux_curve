package com.example.luxcurve.ha

import java.net.URI

/** Everything the Home Assistant integration needs from the settings, as one immutable snapshot. */
data class HaConfig(
    val mqttEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val mqttUser: String = "",
    val mqttPassword: String = "",
    val mqttTls: Boolean = false,
    val deviceName: String = "Wall panel",
    val nodeId: String = "panel",
    val discoveryPrefix: String = "homeassistant",
    /** Announcements and speaker volume. */
    val speakerEnabled: Boolean = true,
    /** Voice commands: the feature is configured (its entities exist in Home Assistant). */
    val voiceEnabled: Boolean = false,
    /** Voice commands: listening is currently allowed (the "Voice listening" switch in Home Assistant). */
    val voiceActive: Boolean = true,
    /** Stream the microphone continuously and let Home Assistant detect the wake word. */
    val voiceWake: Boolean = false,
    /** Start listening when somebody walks up to the panel. */
    val voiceApproach: Boolean = false,
    /** Speak Home Assistant's answer through the panel speaker. */
    val voiceSpeak: Boolean = true,
    /** Software gain applied to the microphone signal, 1 = unchanged. */
    val micGain: Float = 1f,
    val haUrl: String = "",
    val haToken: String = "",
    /** Hold a partial wake lock so the connections survive while the screen is off. */
    val keepAwake: Boolean = true,
) {
    val baseTopic: String get() = "luxcurve/$nodeId"

    val mqttConfigured: Boolean get() = mqttEnabled && mqttHost.isNotBlank()

    val haConfigured: Boolean get() = baseUrl().isNotEmpty() && haToken.isNotBlank()

    /** Voice commands are on, allowed right now, and Home Assistant can be reached. */
    val voiceReady: Boolean get() = voiceEnabled && voiceActive && haConfigured

    /** Does anything need the background service? */
    val serviceNeeded: Boolean get() = mqttConfigured || (voiceEnabled && haConfigured)

    fun brokerUri(): String = (if (mqttTls) "ssl" else "tcp") + "://" + mqttHost.trim() + ":" + mqttPort

    /** "192.168.1.5:8123", "http://ha.local:8123/lovelace/0" and so on become "http://host:port". */
    fun baseUrl(): String {
        var u = haUrl.trim()
        if (u.isEmpty()) return ""
        if (!u.contains("://")) u = "http://$u"
        return try {
            val uri = URI(u)
            val scheme = uri.scheme
            val authority = uri.rawAuthority // host and port, also for names with an underscore
            if (scheme == null || authority == null) u.trimEnd('/') else "$scheme://$authority"
        } catch (e: Exception) {
            u.trimEnd('/')
        }
    }

    fun wsUrl(): String? {
        val b = baseUrl()
        if (b.isEmpty()) return null
        val lower = b.lowercase()
        val ws = when {
            lower.startsWith("https://") -> "wss://" + b.substring(8)
            lower.startsWith("http://") -> "ws://" + b.substring(7)
            lower.startsWith("wss://") || lower.startsWith("ws://") -> b
            else -> return null
        }
        return "$ws/api/websocket"
    }

    /** Turns "/api/tts_proxy/x.mp3" into an absolute address on the Home Assistant server. */
    fun resolveUrl(url: String): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
        return baseUrl() + (if (url.startsWith("/")) url else "/$url")
    }

    companion object {
        /** Node ids end up in MQTT topics and entity ids: lower case letters, digits, "_" and "-" only. */
        fun cleanNodeId(raw: String): String {
            val s = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_')
            return s.ifEmpty { "panel" }
        }
    }

    fun sameBroker(o: HaConfig): Boolean =
        mqttHost == o.mqttHost && mqttPort == o.mqttPort && mqttUser == o.mqttUser &&
            mqttPassword == o.mqttPassword && mqttTls == o.mqttTls && nodeId == o.nodeId

    fun sameHa(o: HaConfig): Boolean = baseUrl() == o.baseUrl() && haToken == o.haToken
}
