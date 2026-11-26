package com.example.luxcurve

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.luxcurve.ha.AndroidMic
import com.example.luxcurve.ha.AndroidPlayer
import com.example.luxcurve.ha.AnnounceCmd
import com.example.luxcurve.ha.Announcer
import com.example.luxcurve.ha.DeviceInfo
import com.example.luxcurve.ha.HaBridge
import com.example.luxcurve.ha.HaConfig
import com.example.luxcurve.ha.HaStatus
import com.example.luxcurve.ha.HaWebSocket
import com.example.luxcurve.ha.HandlerScheduler
import com.example.luxcurve.ha.MqttLink
import com.example.luxcurve.ha.PahoMqttLink
import com.example.luxcurve.ha.Proximity
import com.example.luxcurve.ha.VoiceEngine
import com.example.luxcurve.ha.VoiceOutput
import kotlin.math.roundToInt

/**
 * The link to Home Assistant, in the background:
 *  - MQTT: publishes the light and proximity sensors (and more) and takes announcements and volume commands,
 *  - Assist: streams the microphone to Home Assistant for voice commands and plays the answer.
 * It runs next to [BrightnessService] and does not depend on it.
 */
class HaService : Service(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sched: HandlerScheduler
    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager

    private lateinit var player: AndroidPlayer
    private lateinit var link: HaWebSocket
    private lateinit var announcer: Announcer
    private lateinit var engine: VoiceEngine

    // Used on the worker thread only.
    private var bridge: HaBridge? = null
    private var mqtt: PahoMqttLink? = null
    private var lastLux = -1f
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var cfg: HaConfig = HaConfig()

    private val reloadRunnable = Runnable { reload() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Several keys are written one after the other: look at the result once.
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, 300)
    }

    private val tick = object : Runnable {
        override fun run() {
            publishState()
            handler.postDelayed(this, 1000)
        }
    }

    private val mqttListener = object : MqttLink.Listener {
        override fun onConnected() {
            HaStatus.mqtt = "Connected to " + cfg.mqttHost
            bridge?.onConnected()
        }

        override fun onDisconnected(reason: String?) {
            HaStatus.mqtt = if (reason.isNullOrBlank()) "Disconnected" else "Disconnected: $reason"
            bridge?.onDisconnected()
        }

        override fun onMessage(topic: String, payload: String, retained: Boolean) {
            bridge?.onMessage(topic, payload, retained)
        }
    }

    private val actions = object : HaBridge.Actions {
        override fun setVolume(percent: Int) {
            player.setVolumePercent(percent)
            bridge?.volume(player.volumePercent())
        }

        override fun announce(cmd: AnnounceCmd) {
            announcer.enqueue(cmd)
        }

        override fun stopAudio() {
            // Our own reply first, so it is not spoken again with the on-device voice.
            engine.stopSpeaking()
            sched.post { announcer.stop() }
        }

        override fun listen() {
            engine.listen()
        }

        override fun setVoiceListening(on: Boolean) {
            prefs.voiceActive = on
        }
    }

    private val voiceOutput = object : VoiceOutput {
        override fun state(state: String) {
            HaStatus.voice = state
            bridge?.voiceState(state)
        }

        override fun detail(text: String?) {
            val blocked = text == VoiceEngine.SILENT_MESSAGE && cfg.voiceEnabled && !HaStatus.micService
            HaStatus.voiceDetail = if (blocked) MIC_BLOCKED else text
        }

        override fun command(text: String) {
            HaStatus.lastCommand = text
            bridge?.voiceCommand(text)
        }

        override fun response(text: String) {
            HaStatus.lastReply = text
            bridge?.voiceResponse(text)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        cfg = prefs.haConfig()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        power = getSystemService(POWER_SERVICE) as PowerManager

        thread = HandlerThread("lux-ha")
        thread.start()
        handler = Handler(thread.looper)
        sched = HandlerScheduler(handler)

        startInForeground()

        player = AndroidPlayer(this, sched)
        link = HaWebSocket(sched) { cfg }
        announcer = Announcer(sched, link, player) { cfg }
        engine = VoiceEngine(sched, link, AndroidMic(this), player, voiceOutput, cfg)

        HaStatus.running = true
        HaStatus.voice = if (cfg.voiceReady) "Starting" else "Off"
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        handler.post { onStarted() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Again on every start: this is where the microphone type is added once the permission is there.
        startInForeground()
        when (intent?.action) {
            ACTION_LISTEN -> engine.listen() // hops to the worker thread by itself
            ACTION_TEST -> handler.post {
                announcer.enqueue(AnnounceCmd("This is a test of the speaker of the wall panel.", null, null))
            }
            else -> handler.post { reload() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        HaStatus.running = false
        HaStatus.mqtt = "Off"
        HaStatus.voice = "Off"
        handler.removeCallbacks(reloadRunnable)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        sensors.unregisterListener(this)
        handler.post { cleanUp() }
        // The clean-up posts work of its own, so the thread ends a little later.
        val t = thread
        handler.postDelayed({ t.quitSafely() }, 2500)
        super.onDestroy()
    }

    private fun cleanUp() {
        handler.removeCallbacks(tick)
        engine.stop()
        sched.post { announcer.stop() }
        stopMqtt(false)
        link.close()
        sched.post { player.release() }
        releaseLocks()
    }

    private fun onStarted() {
        startMqtt()
        registerSensors()
        updateLocks()
        engine.start()
        if (cfg.voiceReady) link.whenReady(10_000L) { _, _ -> }
        handler.post(tick)
    }

    // ---------------------------------------------------------------- configuration

    private fun reload() {
        val old = cfg
        val new = prefs.haConfig()
        if (new == old) return
        cfg = new
        if (!new.serviceNeeded) {
            stopSelf()
            return
        }
        val nodeChanged = old.nodeId != new.nodeId || old.discoveryPrefix != new.discoveryPrefix
        if (old.mqttConfigured != new.mqttConfigured || !new.sameBroker(old) || nodeChanged) {
            stopMqtt(nodeChanged && old.mqttConfigured)
            startMqtt()
        } else {
            val b = bridge
            if (b != null) {
                b.updateConfig(new)
                b.voiceListening(new.voiceActive)
            }
        }
        if (!new.sameHa(old)) link.reset()
        engine.updateConfig(new)
        updateLocks()
    }

    private fun startMqtt() {
        val c = cfg
        if (!c.mqttConfigured) {
            HaStatus.mqtt = "Off"
            return
        }
        HaStatus.mqtt = "Connecting…"
        val l = PahoMqttLink(sched, c)
        val b = HaBridge(c, l, deviceInfo(), actions) { sched.now() }
        mqtt = l
        bridge = b
        b.voiceListening(c.voiceActive)
        b.volume(player.volumePercent())
        l.start(mqttListener)
    }

    /** [retire]: also remove the entities from Home Assistant (the panel got another name). */
    private fun stopMqtt(retire: Boolean) {
        val b = bridge
        val l = mqtt
        bridge = null
        mqtt = null
        if (b != null) {
            if (retire) b.retire() else b.close()
        }
        l?.stop()
    }

    @Suppress("DEPRECATION")
    private fun deviceInfo(): DeviceInfo {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        val maker = Build.MANUFACTURER
        return DeviceInfo(
            if (maker.isNullOrBlank()) "Android" else maker,
            Build.MODEL ?: "Wall panel",
            "Lux Curve $version",
        )
    }

    // ---------------------------------------------------------------- sensors and state

    private fun registerSensors() {
        sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                lastLux = event.values[0]
                bridge?.lux(lastLux)
            }
            Sensor.TYPE_PROXIMITY -> {
                val value = event.values[0]
                val near = Proximity.isNear(value, event.sensor.maximumRange, prefs.radarMode, prefs.proxThresholdCm)
                val b = bridge
                if (b != null) {
                    b.proximity(value)
                    b.presence(near)
                }
                engine.onPresence(near)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Once a second: the values that nobody pushes, and a chance for the light sensor's heartbeat. */
    private fun publishState() {
        val b = bridge ?: return
        b.lux(lastLux)
        val percent = systemBrightnessPercent()
        if (percent >= 0) b.brightness(percent)
        b.screenOn(power.isInteractive && !Live.screenOff)
        b.volume(player.volumePercent())
        if (Live.running) {
            val t = Live.temperature
            if (!t.isNaN()) b.temperature(t)
            val h = Live.humidity
            if (!h.isNaN()) b.humidity(h)
        }
    }

    private fun systemBrightnessPercent(): Int = try {
        (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f).roundToInt()
    } catch (e: Settings.SettingNotFoundException) {
        -1
    }

    // ---------------------------------------------------------------- keeping the connection alive

    private fun updateLocks() {
        if (cfg.keepAwake) acquireLocks() else releaseLocks()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val cpu: PowerManager.WakeLock = cpuLock ?: newCpuLock()
        if (!cpu.isHeld) cpu.acquire()
        val wifi: WifiManager.WifiLock? = wifiLock ?: newWifiLock()
        if (wifi != null && !wifi.isHeld) wifi.acquire()
    }

    private fun newCpuLock(): PowerManager.WakeLock {
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luxcurve:ha")
        lock.setReferenceCounted(false)
        cpuLock = lock
        return lock
    }

    @Suppress("DEPRECATION")
    private fun newWifiLock(): WifiManager.WifiLock? {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock = manager.createWifiLock(mode, "luxcurve:ha")
        lock.setReferenceCounted(false)
        wifiLock = lock
        return lock
    }

    private fun releaseLocks() {
        val cpu = cpuLock
        if (cpu != null && cpu.isHeld) cpu.release()
        val wifi = wifiLock
        if (wifi != null && wifi.isHeld) wifi.release()
    }

    // ---------------------------------------------------------------- foreground

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Home Assistant link", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, HomeAssistantActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lux)
            .setContentTitle("Home Assistant link is running")
            .setContentText("Sensors, announcements and voice commands")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val wantMic = prefs.voiceEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var withMic = false
        if (wantMic) {
            try {
                enterForeground(notification, true)
                withMic = true
            } catch (e: Exception) {
                // Android 14 only lets an app start a microphone service while it is on screen,
                // not from the boot receiver. Without it the microphone delivers silence.
            }
        }
        if (!withMic) {
            try {
                enterForeground(notification, false)
            } catch (e: Exception) {
                // Android 12 and later refuse this when the system restarts the service while the app is in
                // the background. Stop here: opening the app (or the next boot) starts it again.
                HaStatus.micService = false
                stopSelf()
                return
            }
        }
        HaStatus.micService = withMic
    }

    private fun enterForeground(notification: Notification, withMic: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "lux_curve_ha"
        private const val NOTIFICATION_ID = 2
        const val ACTION_LISTEN = "com.example.luxcurve.action.LISTEN"
        const val ACTION_TEST = "com.example.luxcurve.action.TEST_ANNOUNCEMENT"

        const val MIC_BLOCKED =
            "Android blocks the microphone for this service. Open the Lux Curve app once so it can use the microphone."

        /** Starts the service when the settings need it and stops it when they do not. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, HaService::class.java)
            if (Prefs(app).haConfig().serviceNeeded) {
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (e: Exception) {
                    // Not allowed from the background right now; the next start from the screen will do it.
                }
            } else {
                app.stopService(intent)
            }
        }

        fun send(context: Context, action: String) {
            val app = context.applicationContext
            if (!Prefs(app).haConfig().serviceNeeded) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, HaService::class.java).setAction(action))
            } catch (e: Exception) {
                // see sync
            }
        }
    }
}
