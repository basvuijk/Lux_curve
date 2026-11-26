package com.example.luxcurve.ha

/** Helpers for 16-bit little-endian mono PCM, the format the microphone path uses. */
object Pcm {
    const val SAMPLE_RATE = 16000

    /** Multiplies every sample in place and clips at the 16-bit limits. */
    fun applyGain(buf: ByteArray, len: Int, gain: Float) {
        if (gain == 1f) return
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt() // sign extended
            var s = (hi shl 8) or lo
            s = (s * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (s and 0xFF).toByte()
            buf[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** True when every byte is zero, which is what Android delivers when it blocks the microphone. */
    fun isSilent(buf: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (buf[i].toInt() != 0) return false
        return true
    }
}
