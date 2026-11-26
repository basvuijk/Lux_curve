package com.example.luxcurve.ha

import com.google.gson.JsonArray
import com.google.gson.JsonObject

class DeviceInfo(val manufacturer: String, val model: String, val swVersion: String)

/** A Home Assistant MQTT discovery message. A null payload removes the entity again. */
class DiscoveryMessage(val topic: String, val payload: String?)

/** Values of the "Voice status" sensor. */
object VoiceState {
    const val DISABLED = "disabled"
    const val IDLE = "idle"
    const val WAKE_WORD = "wake_word"
    const val LISTENING = "listening"
    const val PROCESSING = "processing"
    const val SPEAKING = "speaking"
    const val ERROR = "error"
    val ALL = listOf(DISABLED, IDLE, WAKE_WORD, LISTENING, PROCESSING, SPEAKING, ERROR)
}

/** Every MQTT topic of one panel, all below luxcurve/<node id>. */
class Topics(val base: String) {
    /** Availability: "online", and "offline" as the last will of the connection. */
    val status = "$base/status"

    val lux = "$base/lux"
    val proximity = "$base/proximity"
    val presence = "$base/presence"
    val brightness = "$base/brightness"
    val screen = "$base/screen"
    val temperature = "$base/temperature"
    val humidity = "$base/humidity"

    val volume = "$base/volume"
    val volumeSet = "$base/volume/set"
    val announceSet = "$base/announce/set"
    val playSet = "$base/play/set"
    val stopSet = "$base/stop/set"

    val listenSet = "$base/listen/set"
    val voiceSet = "$base/voice/set"
    val voiceListening = "$base/voice/listening"
    val voiceState = "$base/voice/state"
    val voiceCommand = "$base/voice/command"
    val voiceResponse = "$base/voice/response"
}

/**
 * Builds the MQTT discovery messages that make Home Assistant create the panel's entities.
 * Entities of a feature that is switched off are removed again with an empty retained message.
 */
object HaDiscovery {

    fun messages(cfg: HaConfig, info: DeviceInfo, climate: Boolean): List<DiscoveryMessage> {
        val t = Topics(cfg.baseTopic)
        val b = Builder(cfg, info, t)
        val speaker = cfg.speakerEnabled
        val voice = cfg.voiceEnabled

        b.add("sensor", "illuminance", true, "Illuminance") {
            addProperty("device_class", "illuminance")
            addProperty("state_class", "measurement")
            addProperty("unit_of_measurement", "lx")
            addProperty("state_topic", t.lux)
        }
        b.add("sensor", "proximity", true, "Proximity") {
            addProperty("device_class", "distance")
            addProperty("state_class", "measurement")
            addProperty("unit_of_measurement", "cm")
            addProperty("state_topic", t.proximity)
        }
        b.add("binary_sensor", "presence", true, "Presence") {
            addProperty("device_class", "occupancy")
            addProperty("state_topic", t.presence)
            addProperty("payload_on", "ON")
            addProperty("payload_off", "OFF")
        }
        b.add("sensor", "screen_brightness", true, "Screen brightness") {
            addProperty("state_class", "measurement")
            addProperty("unit_of_measurement", "%")
            addProperty("icon", "mdi:brightness-6")
            addProperty("state_topic", t.brightness)
        }
        b.add("binary_sensor", "screen", true, "Screen") {
            addProperty("icon", "mdi:monitor")
            addProperty("state_topic", t.screen)
            addProperty("payload_on", "ON")
            addProperty("payload_off", "OFF")
        }
        // Only panels that really have the chip get these two.
        b.add("sensor", "temperature", climate, "Temperature") {
            addProperty("device_class", "temperature")
            addProperty("state_class", "measurement")
            addProperty("unit_of_measurement", "°C")
            addProperty("state_topic", t.temperature)
        }
        b.add("sensor", "humidity", climate, "Humidity") {
            addProperty("device_class", "humidity")
            addProperty("state_class", "measurement")
            addProperty("unit_of_measurement", "%")
            addProperty("state_topic", t.humidity)
        }

        b.add("number", "volume", speaker, "Speaker volume") {
            addProperty("icon", "mdi:volume-high")
            addProperty("command_topic", t.volumeSet)
            addProperty("state_topic", t.volume)
            addProperty("min", 0)
            addProperty("max", 100)
            addProperty("step", 1)
            addProperty("unit_of_measurement", "%")
            addProperty("mode", "slider")
        }
        b.add("notify", "announce", speaker, "Announce") {
            addProperty("icon", "mdi:bullhorn")
            addProperty("command_topic", t.announceSet)
        }
        b.add("text", "play_url", speaker, "Play URL") {
            addProperty("icon", "mdi:play-network")
            addProperty("command_topic", t.playSet)
            addProperty("max", 255)
        }
        b.add("button", "stop_audio", speaker, "Stop audio") {
            addProperty("icon", "mdi:stop")
            addProperty("command_topic", t.stopSet)
            addProperty("payload_press", "PRESS")
        }

        b.add("switch", "voice_listening", voice, "Voice listening") {
            addProperty("icon", "mdi:microphone")
            addProperty("command_topic", t.voiceSet)
            addProperty("state_topic", t.voiceListening)
            addProperty("payload_on", "ON")
            addProperty("payload_off", "OFF")
        }
        b.add("button", "listen", voice, "Listen") {
            addProperty("icon", "mdi:microphone-message")
            addProperty("command_topic", t.listenSet)
            addProperty("payload_press", "PRESS")
        }
        b.add("sensor", "voice_state", voice, "Voice status") {
            addProperty("device_class", "enum")
            val options = JsonArray()
            for (s in VoiceState.ALL) options.add(s)
            add("options", options)
            addProperty("icon", "mdi:account-voice")
            addProperty("state_topic", t.voiceState)
        }
        b.add("sensor", "voice_command", voice, "Last voice command") {
            addProperty("icon", "mdi:comment-text")
            addProperty("state_topic", t.voiceCommand)
        }
        b.add("sensor", "voice_response", voice, "Last voice response") {
            addProperty("icon", "mdi:comment-processing")
            addProperty("state_topic", t.voiceResponse)
        }
        return b.out
    }

    private class Builder(val cfg: HaConfig, val info: DeviceInfo, val t: Topics) {
        val out = ArrayList<DiscoveryMessage>()

        fun add(component: String, id: String, enabled: Boolean, name: String, fill: JsonObject.() -> Unit) {
            val topic = cfg.discoveryPrefix + "/" + component + "/" + cfg.nodeId + "/" + id + "/config"
            if (!enabled) {
                out.add(DiscoveryMessage(topic, null))
                return
            }
            val o = JsonObject()
            o.addProperty("name", name)
            o.addProperty("unique_id", "luxcurve_" + cfg.nodeId + "_" + id)
            o.addProperty("availability_topic", t.status)
            o.addProperty("payload_available", "online")
            o.addProperty("payload_not_available", "offline")
            o.add("device", device())
            o.fill()
            out.add(DiscoveryMessage(topic, o.toString()))
        }

        private fun device(): JsonObject {
            val d = JsonObject()
            val ids = JsonArray()
            ids.add("luxcurve_" + cfg.nodeId)
            d.add("identifiers", ids)
            d.addProperty("name", cfg.deviceName.ifBlank { "Wall panel" })
            d.addProperty("manufacturer", info.manufacturer)
            d.addProperty("model", info.model)
            d.addProperty("sw_version", info.swVersion)
            return d
        }
    }
}
