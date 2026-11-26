package com.example.luxcurve.ha

import android.os.Handler
import android.os.SystemClock

/** Runs the integration code on the thread of [handler]. */
class HandlerScheduler(private val handler: Handler) : Scheduler {

    override fun post(block: () -> Unit) {
        handler.post(Runnable { block() })
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): Cancelable {
        val r = Runnable { block() }
        handler.postDelayed(r, delayMs)
        return Cancelable { handler.removeCallbacks(r) }
    }

    override fun now(): Long = SystemClock.elapsedRealtime()
}
