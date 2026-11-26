package com.example.luxcurve

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var loading = true

    private lateinit var swEnable: MaterialSwitch
    private lateinit var swIdle: MaterialSwitch
    private lateinit var swLock: MaterialSwitch
    private lateinit var swProx: MaterialSwitch
    private lateinit var swRadar: MaterialSwitch
    private lateinit var sbIdle: Slider
    private lateinit var sbProx: Slider
    private lateinit var tvStatus: TextView
    private lateinit var tvLux: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvProx: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvClimateInfo: TextView
    private lateinit var tvWarn: TextView
    private lateinit var tvIdleValue: TextView
    private lateinit var tvProxValue: TextView
    private lateinit var tvProxInfo: TextView
    private lateinit var permWrite: View
    private lateinit var permOverlay: View
    private lateinit var permAdmin: View
    private lateinit var curveView: CurveView

    private var hasProx = false
    private var proxMax = 5f

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()

        prefs = Prefs(this)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, AdminReceiver::class.java)

        tvStatus = findViewById(R.id.tvStatus)
        tvLux = findViewById(R.id.tvLux)
        tvPercent = findViewById(R.id.tvPercent)
        tvProx = findViewById(R.id.tvProx)
        tvTemp = findViewById(R.id.tvTemp)
        tvHumidity = findViewById(R.id.tvHumidity)
        tvClimateInfo = findViewById(R.id.tvClimateInfo)
        tvWarn = findViewById(R.id.tvWarn)
        tvIdleValue = findViewById(R.id.tvIdleValue)
        tvProxValue = findViewById(R.id.tvProxValue)
        tvProxInfo = findViewById(R.id.tvProxInfo)
        swEnable = findViewById(R.id.swEnable)
        sbIdle = findViewById(R.id.sbIdle)
        sbProx = findViewById(R.id.sbProx)
        curveView = findViewById(R.id.curveView)

        swIdle = bindSwitchRow(
            R.id.rowIdle,
            "Turn screen off when idle",
            "Switch the screen off after a period without activity"
        )
        swLock = bindSwitchRow(
            R.id.rowLock,
            "Fully switch off",
            "Lock the device instead of showing a black screen"
        )
        swProx = bindSwitchRow(
            R.id.rowProx,
            "Wake on proximity",
            "Switch the screen on when something is near the sensor"
        )
        swRadar = bindSwitchRow(
            R.id.rowRadar,
            "Radar presence mode",
            "Wake on any reading other than 0, for radar sensors"
        )

        permWrite = bindPermissionRow(
            R.id.rowPermWrite,
            "Modify system settings",
            "Needed to change the screen brightness"
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        permOverlay = bindPermissionRow(
            R.id.rowPermOverlay,
            "Display over other apps",
            "Needed for the screen-off timer and touch detection"
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        permAdmin = bindPermissionRow(
            R.id.rowPermAdmin,
            "Device admin",
            "Optional, for a real screen off"
        ) {
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin) else requestAdmin()
        }

        // Proximity sensor range decides the slider range.
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val proxSensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proxSensor != null) {
            hasProx = true
            val range = proxSensor.maximumRange
            proxMax = (floor(range * 2f) / 2f).coerceAtLeast(1f)
            tvProxInfo.text = "Sensor range 0 to " + format1(range) + " cm. Many sensors only " +
                "report near or far, so any value in between behaves the same."
        } else {
            hasProx = false
            tvProxInfo.text = "This device has no proximity sensor."
        }

        // Initial values. Listeners are attached afterwards and guarded by `loading`.
        swEnable.isChecked = prefs.enabled
        swIdle.isChecked = prefs.idleEnabled
        swLock.isChecked = prefs.useLock
        swProx.isChecked = prefs.proxEnabled
        swRadar.isChecked = prefs.radarMode

        sbIdle.valueFrom = 5f
        sbIdle.valueTo = 900f
        sbIdle.stepSize = 5f
        val idle = (((prefs.idleSeconds - 5) / 5f).roundToInt() * 5 + 5).coerceIn(5, 900)
        sbIdle.value = idle.toFloat()
        sbIdle.setLabelFormatter { formatDuration(it.toInt()) }
        tvIdleValue.text = "Turn off after " + formatDuration(idle)

        sbProx.valueFrom = 0f
        sbProx.valueTo = proxMax
        sbProx.stepSize = 0.5f
        val threshold = ((prefs.proxThresholdCm * 2f).roundToInt() / 2f).coerceIn(0f, proxMax)
        sbProx.value = threshold
        sbProx.setLabelFormatter { format1(it) + " cm" }
        updateProxText()

        curveView.points = prefs.curve
        curveView.onCurveChanged = { prefs.curve = it }

        swEnable.setOnCheckedChangeListener { _, on -> if (!loading) setServiceEnabled(on) }
        swIdle.setOnCheckedChangeListener { _, on ->
            if (!loading) prefs.idleEnabled = on
            updateEnabledStates()
        }
        swProx.setOnCheckedChangeListener { _, on ->
            if (!loading) prefs.proxEnabled = on
            updateEnabledStates()
        }
        swRadar.setOnCheckedChangeListener { _, on ->
            if (!loading) prefs.radarMode = on
            updateProxText()
            updateEnabledStates()
        }
        swLock.setOnCheckedChangeListener { _, on ->
            if (!loading) {
                prefs.useLock = on
                if (on && !dpm.isAdminActive(admin)) requestAdmin()
            }
        }
        sbIdle.addOnChangeListener { _, value, fromUser ->
            tvIdleValue.text = "Turn off after " + formatDuration(value.toInt())
            if (fromUser) prefs.idleSeconds = value.toInt()
        }
        sbProx.addOnChangeListener { _, value, fromUser ->
            updateProxText(value)
            if (fromUser) prefs.proxThresholdCm = value
        }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            prefs.curve = Curve.DEFAULT.copyOf()
            curveView.points = prefs.curve
        }

        loading = false
        updateEnabledStates()

        // Make sure the service runs again after the app was updated or the process was killed.
        if (prefs.enabled) startForegroundService(Intent(this, BrightnessService::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- setup helpers

    /** Draw behind the system bars and keep the content clear of them. */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun bindSwitchRow(rowId: Int, title: String, subtitle: String): MaterialSwitch {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSubtitle).text = subtitle
        val sw = row.findViewById<MaterialSwitch>(R.id.rowSwitch)
        row.setOnClickListener { sw.toggle() }
        return sw
    }

    private fun bindPermissionRow(
        rowId: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.permTitle).text = title
        row.findViewById<TextView>(R.id.permSubtitle).text = subtitle
        row.setOnClickListener { onClick() }
        return row
    }

    private fun setPermissionState(row: View, granted: Boolean, onText: String, offText: String) {
        val state = row.findViewById<TextView>(R.id.permState)
        state.text = if (granted) onText else offText + "  ›"
        val attr = if (granted) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        state.setTextColor(MaterialColors.getColor(state, attr))
    }

    private fun updateEnabledStates() {
        sbIdle.isEnabled = swIdle.isChecked
        tvIdleValue.alpha = if (swIdle.isChecked) 1f else 0.5f
        val proxSlider = hasProx && swProx.isChecked && !swRadar.isChecked
        sbProx.isEnabled = proxSlider
        tvProxValue.alpha = if (proxSlider) 1f else 0.5f
    }

    /** The line above the distance slider; in radar mode there is no distance to set. */
    private fun updateProxText(value: Float = sbProx.value) {
        tvProxValue.text = if (swRadar.isChecked) {
            "Radar mode: any reading other than 0 wakes the screen"
        } else {
            "Wake when closer than " + format1(value) + " cm"
        }
    }

    private fun setServiceEnabled(on: Boolean) {
        prefs.enabled = on
        val intent = Intent(this, BrightnessService::class.java)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(intent)
        } else {
            stopService(intent)
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Needed to really switch the screen off (lock the device)."
            )
        startActivity(intent)
    }

    // ---------------------------------------------------------------- live updates

    private fun refresh() {
        tvStatus.text = when {
            !Live.running -> "Stopped"
            Live.screenOff -> "Screen off"
            else -> "Running"
        }
        tvLux.text = luxText(Live.lux)
        tvPercent.text = if (Live.percent < 0f) "–" else Live.percent.roundToInt().toString() + " %"
        tvProx.text = when {
            Live.proximity < 0f -> "–"
            prefs.radarMode -> format1(Live.proximity)
            else -> format1(Live.proximity) + " cm"
        }
        tvTemp.text =
            if (Live.running && !Live.temperature.isNaN()) format1(Live.temperature) + " °C" else "–"
        tvHumidity.text =
            if (Live.running && !Live.humidity.isNaN()) Live.humidity.roundToInt().toString() + " %" else "–"
        val climateError = Live.climateError
        if (Live.running && climateError != null) {
            tvClimateInfo.text = "Climate sensor not readable: $climateError"
            tvClimateInfo.visibility = View.VISIBLE
        } else {
            tvClimateInfo.visibility = View.GONE
        }
        curveView.liveLux = Live.lux

        val canWrite = Settings.System.canWrite(this)
        val canOverlay = Settings.canDrawOverlays(this)
        setPermissionState(permWrite, canWrite, "Allowed", "Allow")
        setPermissionState(permOverlay, canOverlay, "Allowed", "Allow")
        setPermissionState(permAdmin, dpm.isAdminActive(admin), "Active", "Set up")

        val warning: String? = when {
            !canWrite -> "Allow “Modify system settings” below so the curve can change the brightness."
            prefs.idleEnabled && !canOverlay ->
                "Allow “Display over other apps” below to use the screen-off timer."
            else -> null
        }
        if (warning == null) {
            tvWarn.visibility = View.GONE
        } else {
            tvWarn.text = warning
            tvWarn.visibility = View.VISIBLE
        }
    }

    private fun luxText(lux: Float): String = when {
        lux < 0f -> "–"
        lux < 10f -> format1(lux)
        else -> lux.roundToInt().toString()
    }

    private fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "$s s"
            s == 0 -> "$m min"
            else -> "$m min $s s"
        }
    }
}
