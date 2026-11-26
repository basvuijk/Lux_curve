package com.example.luxcurve

import android.content.Context
import android.content.SharedPreferences
import com.example.luxcurve.ha.HaConfig

/** All user settings, stored in SharedPreferences. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lux_curve", Context.MODE_PRIVATE)

    /** Is the background service supposed to be running (also used to restart after boot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Brightness (0..100 %) at each of the fixed lux positions in [Curve.POINTS_LUX]. */
    var curve: FloatArray
        get() {
            val parts = sp.getString("curve", null)?.split(",")
            if (parts == null || parts.size != Curve.POINT_COUNT) return Curve.DEFAULT.copyOf()
            return try {
                FloatArray(Curve.POINT_COUNT) { parts[it].toFloat().coerceIn(0f, 100f) }
            } catch (e: NumberFormatException) {
                Curve.DEFAULT.copyOf()
            }
        }
        set(v) = sp.edit().putString("curve", v.joinToString(",")).apply()

    /** Turn the screen off after [idleSeconds] without activity. */
    var idleEnabled: Boolean
        get() = sp.getBoolean("idle_enabled", false)
        set(v) = sp.edit().putBoolean("idle_enabled", v).apply()

    var idleSeconds: Int
        get() = sp.getInt("idle_seconds", 60)
        set(v) = sp.edit().putInt("idle_seconds", v.coerceIn(1, 86400)).apply()

    /** true = really lock/turn off the screen (needs device admin), false = black overlay at brightness 0. */
    var useLock: Boolean
        get() = sp.getBoolean("use_lock", false)
        set(v) = sp.edit().putBoolean("use_lock", v).apply()

    /** Switch the screen back on when the proximity sensor reports something near. */
    var proxEnabled: Boolean
        get() = sp.getBoolean("prox_enabled", false)
        set(v) = sp.edit().putBoolean("prox_enabled", v).apply()

    /** "Near" means: sensor distance in cm is less than or equal to this value. */
    var proxThresholdCm: Float
        get() = sp.getFloat("prox_threshold", 1f)
        set(v) = sp.edit().putFloat("prox_threshold", v.coerceAtLeast(0f)).apply()

    /**
     * Radar presence sensors report 0 when nobody is there and something else when somebody is.
     * With this on, any reading other than 0 counts as "near" and the distance is not compared.
     */
    var radarMode: Boolean
        get() = sp.getBoolean("radar_mode", false)
        set(v) = sp.edit().putBoolean("radar_mode", v).apply()

    // ---------------------------------------------------------------- Home Assistant

    /** Publish the sensors and take announcement and volume commands over MQTT. */
    var mqttEnabled: Boolean
        get() = sp.getBoolean("ha_mqtt_enabled", false)
        set(v) = sp.edit().putBoolean("ha_mqtt_enabled", v).apply()

    var mqttHost: String
        get() = sp.getString("ha_mqtt_host", "") ?: ""
        set(v) = sp.edit().putString("ha_mqtt_host", v.trim()).apply()

    var mqttPort: Int
        get() = sp.getInt("ha_mqtt_port", 1883)
        set(v) = sp.edit().putInt("ha_mqtt_port", v.coerceIn(1, 65535)).apply()

    var mqttUser: String
        get() = sp.getString("ha_mqtt_user", "") ?: ""
        set(v) = sp.edit().putString("ha_mqtt_user", v.trim()).apply()

    var mqttPassword: String
        get() = sp.getString("ha_mqtt_password", "") ?: ""
        set(v) = sp.edit().putString("ha_mqtt_password", v).apply()

    var mqttTls: Boolean
        get() = sp.getBoolean("ha_mqtt_tls", false)
        set(v) = sp.edit().putBoolean("ha_mqtt_tls", v).apply()

    /** Name of the device in Home Assistant. */
    var deviceName: String
        get() = sp.getString("ha_device_name", null) ?: "Wall panel"
        set(v) = sp.edit().putString("ha_device_name", v.trim()).apply()

    /** Identifies this panel in topics and entity ids; change it when several panels share a broker. */
    var nodeId: String
        get() = HaConfig.cleanNodeId(sp.getString("ha_node_id", null) ?: "panel")
        set(v) = sp.edit().putString("ha_node_id", HaConfig.cleanNodeId(v)).apply()

    /** Announcements and speaker volume. */
    var speakerEnabled: Boolean
        get() = sp.getBoolean("ha_speaker", true)
        set(v) = sp.edit().putBoolean("ha_speaker", v).apply()

    /** Voice commands through the Home Assistant Assist pipeline. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean("ha_voice_enabled", false)
        set(v) = sp.edit().putBoolean("ha_voice_enabled", v).apply()

    /** The "Voice listening" switch in Home Assistant; off means the microphone stays closed. */
    var voiceActive: Boolean
        get() = sp.getBoolean("ha_voice_active", true)
        set(v) = sp.edit().putBoolean("ha_voice_active", v).apply()

    /** Stream the microphone all the time and let Home Assistant listen for the wake word. */
    var voiceWake: Boolean
        get() = sp.getBoolean("ha_voice_wake", false)
        set(v) = sp.edit().putBoolean("ha_voice_wake", v).apply()

    /** Start listening when somebody walks up to the panel. */
    var voiceApproach: Boolean
        get() = sp.getBoolean("ha_voice_approach", false)
        set(v) = sp.edit().putBoolean("ha_voice_approach", v).apply()

    /** Speak Home Assistant's answer through the panel speaker. */
    var voiceSpeak: Boolean
        get() = sp.getBoolean("ha_voice_speak", true)
        set(v) = sp.edit().putBoolean("ha_voice_speak", v).apply()

    /** Software gain for the microphone, 1 = unchanged. */
    var micGain: Float
        get() = sp.getFloat("ha_mic_gain", 1f)
        set(v) = sp.edit().putFloat("ha_mic_gain", v.coerceIn(0.5f, 8f)).apply()

    /** Address of Home Assistant, for example 192.168.1.20:8123. */
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim()).apply()

    /** Long-lived access token of a Home Assistant user. */
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(v) = sp.edit().putString("ha_token", v.trim()).apply()

    /** Hold a wake lock and a Wi-Fi lock so the connections work while the screen is off. */
    var keepAwake: Boolean
        get() = sp.getBoolean("ha_keep_awake", true)
        set(v) = sp.edit().putBoolean("ha_keep_awake", v).apply()

    fun haConfig(): HaConfig = HaConfig(
        mqttEnabled = mqttEnabled,
        mqttHost = mqttHost,
        mqttPort = mqttPort,
        mqttUser = mqttUser,
        mqttPassword = mqttPassword,
        mqttTls = mqttTls,
        deviceName = deviceName,
        nodeId = nodeId,
        speakerEnabled = speakerEnabled,
        voiceEnabled = voiceEnabled,
        voiceActive = voiceActive,
        voiceWake = voiceWake,
        voiceApproach = voiceApproach,
        voiceSpeak = voiceSpeak,
        micGain = micGain,
        haUrl = haUrl,
        haToken = haToken,
        keepAwake = keepAwake,
    )
}
