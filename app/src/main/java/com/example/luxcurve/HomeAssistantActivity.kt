package com.example.luxcurve

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.luxcurve.ha.HaConfig
import com.example.luxcurve.ha.HaStatus
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/** Settings of the Home Assistant link: MQTT sensors, announcements and voice commands. */
class HomeAssistantActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var swMqtt: MaterialSwitch
    private lateinit var swTls: MaterialSwitch
    private lateinit var swSpeaker: MaterialSwitch
    private lateinit var swVoice: MaterialSwitch
    private lateinit var swWake: MaterialSwitch
    private lateinit var swApproach: MaterialSwitch
    private lateinit var swSpeak: MaterialSwitch
    private lateinit var swKeep: MaterialSwitch
    private lateinit var sbGain: Slider
    private lateinit var tvGain: TextView
    private lateinit var permMic: View

    private lateinit var etHost: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etName: TextInputEditText
    private lateinit var etNode: TextInputEditText
    private lateinit var etUrl: TextInputEditText
    private lateinit var etToken: TextInputEditText

    private lateinit var tvMqtt: TextView
    private lateinit var tvVoice: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tvReply: TextView
    private lateinit var tvProblem: TextView

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_ha)
        setupEdgeToEdge()
        prefs = Prefs(this)

        tvMqtt = findViewById(R.id.tvHaMqtt)
        tvVoice = findViewById(R.id.tvHaVoice)
        tvHeard = findViewById(R.id.tvHaHeard)
        tvReply = findViewById(R.id.tvHaReply)
        tvProblem = findViewById(R.id.tvHaProblem)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etName = findViewById(R.id.etName)
        etNode = findViewById(R.id.etNode)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        sbGain = findViewById(R.id.sbGain)
        tvGain = findViewById(R.id.tvGain)

        swMqtt = bindSwitchRow(
            R.id.rowMqtt,
            "Publish to MQTT",
            "Light, proximity and screen state, and commands from Home Assistant"
        )
        swTls = bindSwitchRow(R.id.rowTls, "Encrypted connection", "Use TLS (usually port 8883)")
        swSpeaker = bindSwitchRow(
            R.id.rowSpeaker,
            "Announcements and volume",
            "Play messages and set the speaker volume from Home Assistant"
        )
        swVoice = bindSwitchRow(
            R.id.rowVoice,
            "Voice commands",
            "Send the microphone to the Assist pipeline of Home Assistant"
        )
        swWake = bindSwitchRow(
            R.id.rowWake,
            "Listen for a wake word",
            "Keeps the microphone open, Home Assistant detects the wake word"
        )
        swApproach = bindSwitchRow(
            R.id.rowApproach,
            "Listen when somebody walks up",
            "Starts a command when the proximity sensor sees someone"
        )
        swSpeak = bindSwitchRow(
            R.id.rowSpeak,
            "Speak the answer",
            "Play Home Assistant's spoken reply through the speaker"
        )
        swKeep = bindSwitchRow(
            R.id.rowKeep,
            "Stay connected with the screen off",
            "Holds a CPU and Wi-Fi lock, recommended for a panel on mains power"
        )
        permMic = bindPermissionRow(R.id.rowPermMic, "Microphone", "Needed for voice commands") {
            onMicRowClicked()
        }

        swMqtt.isChecked = prefs.mqttEnabled
        swTls.isChecked = prefs.mqttTls
        swSpeaker.isChecked = prefs.speakerEnabled
        swVoice.isChecked = prefs.voiceEnabled
        swWake.isChecked = prefs.voiceWake
        swApproach.isChecked = prefs.voiceApproach
        swSpeak.isChecked = prefs.voiceSpeak
        swKeep.isChecked = prefs.keepAwake

        etHost.setText(prefs.mqttHost)
        etPort.setText(prefs.mqttPort.toString())
        etUser.setText(prefs.mqttUser)
        etPass.setText(prefs.mqttPassword)
        etName.setText(prefs.deviceName)
        etNode.setText(prefs.nodeId)
        etUrl.setText(prefs.haUrl)
        etToken.setText(prefs.haToken)

        sbGain.valueFrom = 0.5f
        sbGain.valueTo = 6f
        sbGain.stepSize = 0.5f
        sbGain.value = ((prefs.micGain * 2f).toInt() / 2f).coerceIn(0.5f, 6f)
        sbGain.setLabelFormatter { format1(it) + "x" }
        tvGain.text = gainText(sbGain.value)
        sbGain.addOnChangeListener { _, value, _ -> tvGain.text = gainText(value) }

        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnTest).setOnClickListener {
            save(announce = false)
            if (prefs.haConfig().serviceNeeded) {
                HaService.send(this, HaService.ACTION_TEST)
            } else {
                toast("Switch on MQTT or voice commands first")
            }
        }
        findViewById<View>(R.id.btnListen).setOnClickListener {
            save(announce = false)
            if (prefs.haConfig().voiceReady) {
                HaService.send(this, HaService.ACTION_LISTEN)
            } else {
                toast("Switch on voice commands and fill in the Home Assistant address and token first")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (micGranted()) {
                // This screen is visible, so Android lets the service start with microphone access now.
                HaService.sync(this)
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // Denied for good: only the Android settings can change that.
                openAppSettings()
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------- saving

    /** Writes the screen into the settings and (re)starts the service. [announce]: say what was saved. */
    private fun save(announce: Boolean = true) {
        prefs.mqttEnabled = swMqtt.isChecked
        prefs.mqttHost = text(etHost)
        prefs.mqttPort = text(etPort).toIntOrNull() ?: 1883
        prefs.mqttUser = text(etUser)
        prefs.mqttPassword = etPass.text?.toString() ?: ""
        prefs.mqttTls = swTls.isChecked
        prefs.deviceName = text(etName).ifEmpty { "Wall panel" }
        prefs.nodeId = text(etNode)
        etNode.setText(prefs.nodeId)

        prefs.speakerEnabled = swSpeaker.isChecked
        prefs.voiceEnabled = swVoice.isChecked
        prefs.voiceWake = swWake.isChecked
        prefs.voiceApproach = swApproach.isChecked
        prefs.voiceSpeak = swSpeak.isChecked
        prefs.micGain = sbGain.value
        prefs.haUrl = text(etUrl)
        prefs.haToken = text(etToken)
        prefs.keepAwake = swKeep.isChecked

        val cfg: HaConfig = prefs.haConfig()
        if (cfg.voiceEnabled && !micGranted()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
        // Starts, updates or stops the service. This screen is visible, so Android allows a microphone service.
        HaService.sync(this)

        val problem = when {
            cfg.mqttEnabled && cfg.mqttHost.isBlank() -> "Fill in the broker address to use MQTT"
            cfg.voiceEnabled && !cfg.haConfigured -> "Voice commands need the Home Assistant address and token"
            else -> null
        }
        if (announce || problem != null) toast(problem ?: "Saved")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun text(view: TextInputEditText): String = view.text?.toString()?.trim() ?: ""

    // ---------------------------------------------------------------- microphone permission

    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onMicRowClicked() {
        if (micGranted()) {
            openAppSettings()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    // ---------------------------------------------------------------- status

    private fun refresh() {
        tvMqtt.text = if (prefs.mqttEnabled) HaStatus.mqtt else "Off"
        val voice = HaStatus.voice
        tvVoice.text = when {
            !prefs.voiceEnabled -> "Off"
            !HaStatus.running -> "Not running"
            voice == "disabled" || voice == "Off" -> "Waiting for settings"
            else -> voice.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        showLine(tvHeard, HaStatus.lastCommand?.let { "Heard: $it" })
        showLine(tvReply, HaStatus.lastReply?.let { "Answer: $it" })
        val problems = ArrayList<String>()
        HaStatus.voiceDetail?.let { problems.add(it) }
        HaStatus.speaker?.let { problems.add("Speaker: $it") }
        showLine(tvProblem, if (problems.isEmpty()) null else problems.joinToString("\n"))
        setPermissionState(permMic, micGranted(), "Allowed", "Allow")
        updateEnabledStates()
    }

    private fun showLine(view: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    private fun updateEnabledStates() {
        val voice = swVoice.isChecked
        swWake.isEnabled = voice
        swApproach.isEnabled = voice
        swSpeak.isEnabled = voice
        sbGain.isEnabled = voice
    }

    private fun gainText(value: Float): String = "Microphone gain " + format1(value) + "x"

    private fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)

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
        sw.setOnCheckedChangeListener { _, _ -> updateEnabledStates() }
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

    companion object {
        private const val REQUEST_MIC = 11
    }
}
