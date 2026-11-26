package com.example.luxcurve.ha

/**
 * Plays announcements one after the other. Text is turned into speech by the Home Assistant
 * text-to-speech engine of the default Assist pipeline when Home Assistant is configured (best
 * quality, same voice as everywhere else) and by the on-device engine otherwise or when that fails.
 */
class Announcer(
    private val sched: Scheduler,
    private val link: AssistLink,
    private val player: Player,
    private val config: () -> HaConfig,
) {
    private val queue = ArrayDeque<AnnounceCmd>()
    private var busy = false
    private var run: AssistRun? = null
    private var generation = 0

    fun enqueue(cmd: AnnounceCmd) {
        queue.addLast(cmd)
        pump()
    }

    /** Stops what is playing and drops everything that is waiting. */
    fun stop() {
        generation++
        queue.clear()
        run?.cancel()
        run = null
        busy = false
        player.stop()
    }

    private fun pump() {
        if (busy) return
        val cmd = queue.removeFirstOrNull() ?: return
        busy = true
        process(cmd, generation)
    }

    private fun finish(gen: Int) {
        if (gen != generation) return
        busy = false
        run?.cancel() // a no-op when the run already ended
        run = null
        pump()
    }

    private fun process(cmd: AnnounceCmd, gen: Int) {
        val cfg = config()
        val url = cmd.url
        val message = cmd.message
        when {
            url != null -> player.playUrl(cfg.resolveUrl(url), cmd.volume) { finish(gen) }
            message != null && cfg.haConfigured -> synthesize(message, cmd.volume, gen)
            message != null -> player.speak(message, cmd.volume) { finish(gen) }
            else -> finish(gen)
        }
    }

    private fun synthesize(text: String, volume: Int?, gen: Int) {
        link.whenReady(CONNECT_TIMEOUT_MS) { ok, _ ->
            if (gen != generation) return@whenReady
            if (!ok) {
                player.speak(text, volume) { finish(gen) }
                return@whenReady
            }
            var settled = false
            fun fallback() {
                if (gen != generation || settled) return
                settled = true
                player.speak(text, volume) { finish(gen) }
            }
            val r = AssistRun(
                link,
                AssistRequest(startStage = "tts", endStage = "tts", text = text),
                object : AssistRun.Listener {
                    override fun onAudio(url: String, mime: String?) {
                        if (gen != generation || settled) return
                        settled = true
                        player.playUrl(config().resolveUrl(url), volume) { played ->
                            if (played || gen != generation) {
                                finish(gen)
                            } else {
                                // The audio could not be played (format, network): say it with the on-device voice.
                                player.speak(text, volume) { finish(gen) }
                            }
                        }
                    }

                    override fun onError(code: String, message: String) = fallback()

                    override fun onFinished() = fallback()
                },
            )
            run = r
            r.start()
            sched.postDelayed(SYNTHESIS_TIMEOUT_MS) {
                if (gen == generation && !settled) {
                    r.cancel()
                    fallback()
                }
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val SYNTHESIS_TIMEOUT_MS = 20_000L
    }
}
