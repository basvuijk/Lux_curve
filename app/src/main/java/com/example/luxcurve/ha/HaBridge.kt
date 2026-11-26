package com.example.luxcurve.ha

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decides when a changing number is worth publishing: it must differ from the last published value by
 * at least max([absEps], [relEps] * value) and at least [minMs] must have passed, or [heartbeatMs]
 * (0 = never) has passed since the last publish.
 */
class NumberGate(
    private val absEps: Double,
    private val relEps: Double,
    private val minMs: Long,
    private val heartbeatMs: Long,
) {
    private var last = Double.NaN
    private var lastAt = 0L

    fun accept(value: Double, now: Long): Boolean {
        if (value.isNaN()) return false
        val ok = if (last.isNaN()) {
            true
        } else {
            val threshold = max(absEps, relEps * abs(last))
            (abs(value - last) >= threshold && now - lastAt >= minMs) ||
                (heartbeatMs > 0L && now - lastAt >= heartbeatMs)
        }
        if (ok) {
            last = value
            lastAt = now
        }
        return ok
    }
}

/**
 * The Home Assistant side of the MQTT connection: publishes the discovery messages and the sensor
 * states, and turns incoming command messages into calls on [Actions].
 * Not thread safe: call everything from the scheduler thread.
 */
class HaBridge(
    initial: HaConfig,
    private val link: MqttLink,
    private val info: DeviceInfo,
    private val actions: Actions,
    private val now: () -> Long,
) {
    interface Actions {
        fun setVolume(percent: Int)
        fun announce(cmd: AnnounceCmd)
        fun stopAudio()
        fun listen()
        fun setVoiceListening(on: Boolean)
    }

    var cfg: HaConfig = initial
        private set

    private var topics = Topics(initial.baseTopic)
    private var connected = false
    private var climateKnown = false

    /** Last payload per state topic; replayed after a reconnect and used to skip unchanged values. */
    private val published = LinkedHashMap<String, String>()

    private val luxGate = NumberGate(1.0, 0.05, 1_000L, 60_000L)
    private val brightnessGate = NumberGate(1.0, 0.0, 1_000L, 0L)
    private val temperatureGate = NumberGate(0.1, 0.0, 5_000L, 600_000L)
    private val humidityGate = NumberGate(1.0, 0.0, 5_000L, 600_000L)

    // ------------------------------------------------------------------ connection

    fun onConnected() {
        connected = true
        for (topic in subscriptions()) link.subscribe(topic)
        link.publish(topics.status, "online", true)
        publishDiscovery()
        for ((topic, payload) in published) link.publish(topic, payload, true)
    }

    fun onDisconnected() {
        connected = false
    }

    /** Call before the link is stopped so Home Assistant marks the entities unavailable at once. */
    fun close() {
        if (connected) link.publish(topics.status, "offline", true)
        connected = false
    }

    /** The panel is renamed or switched off for good: remove its entities and retained states from the broker. */
    fun retire() {
        if (connected) {
            val all = cfg.copy(speakerEnabled = true, voiceEnabled = true)
            for (m in HaDiscovery.messages(all, info, true)) link.publish(m.topic, "", true)
            for (topic in published.keys) link.publish(topic, "", true)
            link.publish(topics.status, "", true)
        }
        published.clear()
        connected = false
    }

    /** Feature switches changed: add or remove the entities that belong to them. */
    fun updateConfig(new: HaConfig) {
        cfg = new
        topics = Topics(new.baseTopic)
        if (connected) publishDiscovery()
    }

    private fun subscriptions(): List<String> = listOf(
        topics.volumeSet, topics.announceSet, topics.playSet, topics.stopSet,
        topics.listenSet, topics.voiceSet, cfg.discoveryPrefix + "/status",
    )

    private fun publishDiscovery() {
        for (m in HaDiscovery.messages(cfg, info, climateKnown)) {
            link.publish(m.topic, m.payload ?: "", true)
        }
    }

    // ------------------------------------------------------------------ commands

    fun onMessage(topic: String, payload: String, retained: Boolean) {
        if (topic == cfg.discoveryPrefix + "/status") {
            // Home Assistant just (re)started: it may have lost the retained discovery messages.
            if (payload.trim() == "online" && connected) {
                link.publish(topics.status, "online", true)
                publishDiscovery()
                for ((t, p) in published) link.publish(t, p, true)
            }
            return
        }
        // Commands are one-shot. A retained one would be replayed on every reconnect.
        if (retained) return
        when (topic) {
            topics.volumeSet -> if (cfg.speakerEnabled) Commands.parseVolume(payload)?.let { actions.setVolume(it) }
            topics.announceSet -> if (cfg.speakerEnabled) Commands.parseAnnounce(payload, false)?.let { actions.announce(it) }
            topics.playSet -> if (cfg.speakerEnabled) Commands.parseAnnounce(payload, true)?.let { actions.announce(it) }
            topics.stopSet -> if (cfg.speakerEnabled) actions.stopAudio()
            topics.listenSet -> if (cfg.voiceEnabled) actions.listen()
            topics.voiceSet -> if (cfg.voiceEnabled) Commands.parseSwitch(payload)?.let { actions.setVoiceListening(it) }
        }
    }

    // ------------------------------------------------------------------ state

    fun lux(value: Float) {
        if (value.isNaN() || value < 0f) return
        if (luxGate.accept(value.toDouble(), now())) {
            put(topics.lux, if (value >= 10f) value.roundToInt().toString() else format1(value))
        }
    }

    fun proximity(value: Float) {
        if (value.isNaN() || value < 0f) return
        put(topics.proximity, if (value == floor(value)) value.toInt().toString() else format1(value))
    }

    fun presence(near: Boolean) = put(topics.presence, onOff(near))

    fun brightness(percent: Int) {
        if (percent < 0) return
        if (brightnessGate.accept(percent.toDouble(), now())) put(topics.brightness, percent.toString())
    }

    fun screenOn(on: Boolean) = put(topics.screen, onOff(on))

    fun temperature(celsius: Float) {
        if (celsius.isNaN()) return
        becomeClimate()
        if (temperatureGate.accept(celsius.toDouble(), now())) put(topics.temperature, format1(celsius))
    }

    fun humidity(percent: Float) {
        if (percent.isNaN()) return
        becomeClimate()
        if (humidityGate.accept(percent.toDouble(), now())) put(topics.humidity, percent.roundToInt().toString())
    }

    fun volume(percent: Int) {
        if (percent >= 0) put(topics.volume, percent.toString())
    }

    fun voiceState(state: String) = put(topics.voiceState, state)

    fun voiceListening(on: Boolean) = put(topics.voiceListening, onOff(on))

    /** Always published, so a repeated command still updates the sensor. */
    fun voiceCommand(text: String) = put(topics.voiceCommand, clip(text), force = true)

    fun voiceResponse(text: String) = put(topics.voiceResponse, clip(text), force = true)

    /** The first temperature or humidity reading proves the panel has the chip: create those entities. */
    private fun becomeClimate() {
        if (climateKnown) return
        climateKnown = true
        if (connected) publishDiscovery()
    }

    private fun put(topic: String, payload: String, force: Boolean = false) {
        if (!force && published[topic] == payload) return
        published[topic] = payload
        if (connected) link.publish(topic, payload, true)
    }

    private fun onOff(on: Boolean) = if (on) "ON" else "OFF"

    private fun format1(v: Float) = String.format(Locale.US, "%.1f", v)

    /** A sensor state is limited to 255 characters. */
    private fun clip(text: String): String {
        val one = text.replace('\n', ' ').trim()
        return if (one.length <= 250) one else one.substring(0, 249) + "…"
    }
}
