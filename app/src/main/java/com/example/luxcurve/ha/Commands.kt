package com.example.luxcurve.ha

import kotlin.math.roundToInt

/** An announcement: speak [message], or play the audio at [url] (which wins when both are given). */
data class AnnounceCmd(val message: String?, val url: String?, val volume: Int?)

/** Parsing of the payloads Home Assistant publishes to the command topics. */
object Commands {
    const val MAX_TEXT = 1000

    /**
     * On the announce topic a plain payload is text to speak. On the play topic a plain payload is a URL.
     * A JSON object works on both: {"message": "...", "url": "...", "volume": 0-100}.
     */
    fun parseAnnounce(payload: String, urlTopic: Boolean): AnnounceCmd? {
        val p = payload.trim()
        if (p.isEmpty()) return null
        if (p.startsWith("{")) {
            val o = parseObject(p) ?: return null
            val message = (o.str("message") ?: o.str("text") ?: o.str("tts"))
                ?.trim()?.takeIf { it.isNotEmpty() }
            val url = (o.str("url") ?: o.str("media_url"))?.trim()?.takeIf { isUrl(it) }
            val volume = o.int("volume")?.coerceIn(0, 100)
            if (message == null && url == null) return null
            return AnnounceCmd(message?.take(MAX_TEXT), url, volume)
        }
        return if (urlTopic) {
            if (isUrl(p)) AnnounceCmd(null, p, null) else null
        } else {
            AnnounceCmd(p.take(MAX_TEXT), null, null)
        }
    }

    /** http(s) addresses, or a path on the Home Assistant server such as /local/chime.mp3. */
    fun isUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true) || s.startsWith("/")

    /** "45", "45.0" and " 45 " all mean 45 %. */
    fun parseVolume(payload: String): Int? =
        payload.trim().toFloatOrNull()?.takeIf { !it.isNaN() }?.roundToInt()?.coerceIn(0, 100)

    fun parseSwitch(payload: String): Boolean? = when (payload.trim().uppercase()) {
        "ON", "TRUE", "1" -> true
        "OFF", "FALSE", "0" -> false
        else -> null
    }
}
