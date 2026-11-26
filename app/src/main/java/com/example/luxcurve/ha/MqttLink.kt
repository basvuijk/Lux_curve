package com.example.luxcurve.ha

/**
 * What the bridge needs from an MQTT client. The real implementation wraps Eclipse Paho
 * ([PahoMqttLink]); the tests use an in-memory fake. All callbacks arrive on the scheduler thread.
 */
interface MqttLink {
    interface Listener {
        /** The broker accepted the connection (also after every automatic reconnect). */
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onMessage(topic: String, payload: String, retained: Boolean)
    }

    /** Starts connecting in the background and keeps retrying until [stop]. */
    fun start(listener: Listener)
    fun publish(topic: String, payload: String, retain: Boolean)
    fun subscribe(topic: String)
    /** Closes the connection; no callbacks arrive afterwards. */
    fun stop()
}
