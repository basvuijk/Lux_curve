package com.example.luxcurve.ha

fun interface Cancelable {
    fun cancel()
}

/**
 * Where the integration code runs. Everything in this package that keeps state is meant to be called
 * from one thread only; the Android implementation posts to the service's worker thread, the tests
 * use a fake with a virtual clock.
 */
interface Scheduler {
    fun post(block: () -> Unit)
    fun postDelayed(delayMs: Long, block: () -> Unit): Cancelable
    fun now(): Long
}
