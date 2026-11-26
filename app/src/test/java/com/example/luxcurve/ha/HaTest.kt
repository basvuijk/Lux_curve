// Tests of the pure Kotlin part of the Home Assistant link (the ha package): discovery messages, command
// parsing, MQTT bridge, Assist protocol, announcements and the voice engine. They run on the JVM against
// simulated brokers, sockets, players and microphones with a virtual clock, so no device is needed.
// Run them with `./gradlew testDebugUnitTest`, or from Android Studio.
package com.example.luxcurve.ha

import com.google.gson.JsonParser

var failures = 0
var checks = 0

/** Everything that failed, in order, so that a JUnit run can put it in its failure message. */
val failureLog = StringBuilder()

fun check(cond: Boolean, msg: String) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $msg")
        failureLog.append("FAIL: ").append(msg).append('\n')
    }
}

fun eq(actual: Any?, expected: Any?, msg: String) {
    checks++
    if (actual != expected) {
        failures++
        val text = "FAIL: $msg\n   expected: $expected\n   actual:   $actual"
        println(text)
        failureLog.append(text).append('\n')
    }
}

fun section(name: String) = println("-- $name")

/** 16-bit little-endian samples. */
fun pcm(vararg samples: Int): ByteArray {
    val b = ByteArray(samples.size * 2)
    for ((i, s) in samples.withIndex()) {
        b[i * 2] = (s and 0xFF).toByte()
        b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return b
}

fun loud(n: Int = 320): ByteArray = ByteArray(n * 2) { if (it % 2 == 0) 5 else 1 }

val INFO = DeviceInfo("Smatek", "T10 Pro", "1.2")

fun voiceCfg(vararg tweak: (HaConfig) -> HaConfig): HaConfig {
    var c = HaConfig(voiceEnabled = true, haUrl = "ha:8123", haToken = "tok")
    for (t in tweak) c = t(c)
    return c
}

/** Runs every section; the counters above tell how it went. */
fun runAll() {
    failures = 0
    checks = 0
    failureLog.setLength(0)
    testConfig()
    testCommands()
    testGate()
    testPcm()
    testDiscovery()
    testBridge()
    testBridgeFeatureSwitch()
    testAssistEvents()
    testAssistRun()
    testAnnouncer()
    testVoicePushToTalk()
    testVoiceWake()
    testVoiceFollowUp()
    testVoiceErrors()
    testVoiceRetry()
    testVoiceConfigChanges()
    testVoiceApproach()
    testVoiceMic()
    testVoiceTimers()
    testVoiceNoSpeech()
    testVoiceFallbackPlayback()
}

/** Command line entry point: `java -cp ... com.example.luxcurve.ha.HaTestKt`. */
fun main() {
    runAll()
    println("\n$checks checks, $failures failures")
    if (failures > 0) System.exit(1)
}

// ---------------------------------------------------------------------------------------------

fun testConfig() {
    section("config")
    val c = HaConfig(haUrl = "192.168.1.5:8123", haToken = "t")
    eq(c.baseUrl(), "http://192.168.1.5:8123", "bare ip:port")
    eq(c.wsUrl(), "ws://192.168.1.5:8123/api/websocket", "ws url")
    eq(HaConfig(haUrl = "https://ha.example.com/lovelace/0").wsUrl(), "wss://ha.example.com/api/websocket", "https to wss")
    eq(HaConfig(haUrl = "  http://ha.local:8123/ ").baseUrl(), "http://ha.local:8123", "trailing slash and blanks")
    eq(HaConfig(haUrl = "ha_server:8123").baseUrl(), "http://ha_server:8123", "underscore host")
    eq(HaConfig(haUrl = "").wsUrl(), null, "empty url")
    eq(c.resolveUrl("/api/tts_proxy/x.mp3"), "http://192.168.1.5:8123/api/tts_proxy/x.mp3", "relative url")
    eq(c.resolveUrl("api/x"), "http://192.168.1.5:8123/api/x", "relative without slash")
    eq(c.resolveUrl("https://x/y.mp3"), "https://x/y.mp3", "absolute url kept")
    eq(HaConfig(mqttHost = "b", mqttPort = 1884, mqttTls = true).brokerUri(), "ssl://b:1884", "broker uri")
    check(!HaConfig().serviceNeeded, "default needs no service")
    check(HaConfig(mqttEnabled = true, mqttHost = "b").serviceNeeded, "mqtt needs the service")
    check(!HaConfig(mqttEnabled = true, mqttHost = " ").serviceNeeded, "blank broker host")
    check(HaConfig(voiceEnabled = true, haUrl = "x", haToken = "t").serviceNeeded, "voice needs the service")
    check(!HaConfig(voiceEnabled = true, haUrl = "x").serviceNeeded, "voice without token")
    check(voiceCfg().voiceReady, "voice ready")
    check(!voiceCfg({ it.copy(voiceActive = false) }).voiceReady, "voice switched off")
    check(HaConfig(haUrl = "a", haToken = "b").sameHa(HaConfig(haUrl = "http://a", haToken = "b")), "same Home Assistant")
    check(!HaConfig(haUrl = "a", haToken = "b").sameHa(HaConfig(haUrl = "a", haToken = "c")), "other token")
    eq(HaConfig(nodeId = "kitchen").baseTopic, "luxcurve/kitchen", "base topic")
    eq(HaConfig.cleanNodeId("Kitchen Wall Panel!"), "kitchen_wall_panel", "node id cleaned")
    eq(HaConfig.cleanNodeId("  "), "panel", "empty node id")
    eq(HaConfig.cleanNodeId("hall-2_a"), "hall-2_a", "valid node id kept")
    eq(HaConfig.cleanNodeId("é/ü"), "panel", "only odd characters")
}

fun testCommands() {
    section("commands")
    eq(Commands.parseAnnounce("Dinner is ready", false), AnnounceCmd("Dinner is ready", null, null), "plain text")
    eq(Commands.parseAnnounce("http://x/y.mp3", true), AnnounceCmd(null, "http://x/y.mp3", null), "plain url")
    eq(Commands.parseAnnounce("/local/chime.mp3", true), AnnounceCmd(null, "/local/chime.mp3", null), "path url")
    eq(Commands.parseAnnounce("not a url", true), null, "not a url on the play topic")
    eq(Commands.parseAnnounce("""{"message":"Hi","volume":150}""", false), AnnounceCmd("Hi", null, 100), "json, volume clamped")
    eq(Commands.parseAnnounce("""{"message":"Hi","volume":"60"}""", false), AnnounceCmd("Hi", null, 60), "json, volume as string")
    eq(Commands.parseAnnounce("""{"url":"/local/c.mp3","message":"x"}""", false), AnnounceCmd("x", "/local/c.mp3", null), "json both")
    eq(Commands.parseAnnounce("""{"url":"nonsense"}""", false), null, "json with bad url only")
    eq(Commands.parseAnnounce("{bad json", false), null, "broken json")
    eq(Commands.parseAnnounce("   ", false), null, "blank")
    eq(Commands.parseAnnounce("a".repeat(5000), false)?.message?.length, Commands.MAX_TEXT, "long text is cut")
    eq(Commands.parseVolume(" 45.6 "), 46, "volume rounds")
    eq(Commands.parseVolume("abc"), null, "volume garbage")
    eq(Commands.parseVolume("NaN"), null, "volume NaN")
    eq(Commands.parseVolume("-5"), 0, "volume low clamp")
    eq(Commands.parseVolume("250"), 100, "volume high clamp")
    eq(Commands.parseSwitch("on"), true, "switch on")
    eq(Commands.parseSwitch("OFF"), false, "switch off")
    eq(Commands.parseSwitch("toggle"), null, "switch garbage")
}

fun testGate() {
    section("number gate")
    val g = NumberGate(1.0, 0.05, 1000, 60000)
    check(g.accept(100.0, 0), "first value")
    check(!g.accept(101.0, 2000), "small change ignored")
    check(g.accept(106.0, 2000), "5 percent change passes")
    check(!g.accept(130.0, 2500), "too soon")
    check(g.accept(130.0, 3100), "after the minimum interval")
    check(!g.accept(130.0, 4000), "unchanged")
    check(g.accept(130.0, 3100 + 60000), "heartbeat")
    check(!g.accept(Double.NaN, 999999), "NaN")
}

fun testPcm() {
    section("pcm")
    val a = pcm(1000, -1000, 20000, -20000, 5)
    Pcm.applyGain(a, a.size, 2f)
    eq(a.toList(), pcm(2000, -2000, 32767, -32768, 10).toList(), "gain doubles and clips")
    val b = pcm(1, 2, 3)
    Pcm.applyGain(b, b.size, 1f)
    eq(b.toList(), pcm(1, 2, 3).toList(), "gain 1 leaves the audio alone")
    check(Pcm.isSilent(ByteArray(10), 10), "zeros are silent")
    check(!Pcm.isSilent(pcm(0, 0, 1), 6), "not silent")
    check(Proximity.isNear(0f, 5f, false, 3f), "ordinary sensor near")
    check(!Proximity.isNear(5f, 5f, false, 3f), "ordinary sensor far")
    check(!Proximity.isNear(4f, 5f, false, 3f), "beyond the threshold")
    check(Proximity.isNear(1f, 5f, true, 3f), "radar present")
    check(!Proximity.isNear(0f, 5f, true, 3f), "radar absent")
}

fun testDiscovery() {
    section("discovery")
    val cfg = HaConfig(mqttEnabled = true, mqttHost = "b", nodeId = "hall", deviceName = "Hall panel")
    val msgs = HaDiscovery.messages(cfg, INFO, false)
    val active = msgs.filter { it.payload != null }
    val removed = msgs.filter { it.payload == null }
    eq(active.size, 9, "9 entities without voice and climate")
    eq(removed.size, 7, "2 climate + 5 voice entities removed")
    eq(msgs.map { it.topic }.toSet().size, msgs.size, "topics are unique")
    val ids = HashSet<String>()
    for (m in active) {
        val o = JsonParser.parseString(m.payload!!).asJsonObject
        check(m.topic.startsWith("homeassistant/") && m.topic.endsWith("/config"), "topic shape ${m.topic}")
        check(m.topic.contains("/hall/"), "node id in topic ${m.topic}")
        check(ids.add(o.get("unique_id").asString), "unique id ${o.get("unique_id")}")
        eq(o.get("availability_topic").asString, "luxcurve/hall/status", "availability ${m.topic}")
        eq(o.getAsJsonObject("device").getAsJsonArray("identifiers")[0].asString, "luxcurve_hall", "device id")
        eq(o.getAsJsonObject("device").get("name").asString, "Hall panel", "device name")
        check(!o.has("object_id") && !o.has("default_entity_id"), "no object_id")
    }
    val lux = JsonParser.parseString(active.first { it.topic.endsWith("/illuminance/config") }.payload!!).asJsonObject
    eq(lux.get("device_class").asString, "illuminance", "lux class")
    eq(lux.get("state_topic").asString, "luxcurve/hall/lux", "lux topic")
    val vol = JsonParser.parseString(active.first { it.topic.endsWith("/volume/config") }.payload!!).asJsonObject
    eq(vol.get("command_topic").asString, "luxcurve/hall/volume/set", "volume command topic")
    eq(vol.get("max").asInt, 100, "volume max")

    val full = HaDiscovery.messages(cfg.copy(voiceEnabled = true), INFO, true)
    eq(full.count { it.payload != null }, 16, "all 16 entities")
    val state = JsonParser.parseString(full.first { it.topic.endsWith("/voice_state/config") }.payload!!).asJsonObject
    eq(state.getAsJsonArray("options").size(), VoiceState.ALL.size, "voice state options")
    val noSpeaker = HaDiscovery.messages(cfg.copy(speakerEnabled = false), INFO, false)
    eq(noSpeaker.count { it.payload != null }, 5, "speaker entities removed")
}

fun testBridge() {
    section("bridge")
    val sched = FakeScheduler()
    val mqtt = FakeMqtt()
    val actions = RecordingActions()
    val cfg = HaConfig(mqttEnabled = true, mqttHost = "b", voiceEnabled = true)
    val bridge = HaBridge(cfg, mqtt, INFO, actions) { sched.now() }

    bridge.lux(120f)
    bridge.proximity(5f)
    check(mqtt.published.isEmpty(), "nothing is sent before the connection is up")

    bridge.onConnected()
    check(mqtt.subscribed.contains("luxcurve/panel/volume/set"), "subscribed to volume")
    check(mqtt.subscribed.contains("luxcurve/panel/announce/set"), "subscribed to announce")
    check(mqtt.subscribed.contains("luxcurve/panel/voice/set"), "subscribed to voice switch")
    check(mqtt.subscribed.contains("homeassistant/status"), "subscribed to the birth message")
    eq(mqtt.published.first().first, "luxcurve/panel/status", "status first")
    eq(mqtt.published.first().second, "online", "status online")
    check(mqtt.published.all { it.third }, "everything is retained")
    eq(mqtt.last("luxcurve/panel/lux"), "120", "lux replayed after connect")
    eq(mqtt.last("luxcurve/panel/proximity"), "5", "proximity replayed")
    check((mqtt.last("homeassistant/sensor/panel/illuminance/config") ?: "").startsWith("{"), "illuminance discovery")
    eq(mqtt.last("homeassistant/sensor/panel/temperature/config"), "", "no temperature entity before a reading")

    // throttling
    val n0 = mqtt.published.size
    sched.t = 500
    bridge.lux(121f)
    bridge.lux(300f)
    eq(mqtt.published.size, n0, "lux inside the minimum interval is held back")
    sched.t = 1500
    bridge.lux(300f)
    eq(mqtt.last("luxcurve/panel/lux"), "300", "lux after the interval")
    bridge.lux(3.456f)
    sched.t = 5000
    bridge.lux(3.456f)
    eq(mqtt.last("luxcurve/panel/lux"), "3.5", "small lux with a decimal")

    bridge.proximity(3.5f)
    eq(mqtt.last("luxcurve/panel/proximity"), "3.5", "proximity decimal")
    bridge.presence(true)
    eq(mqtt.last("luxcurve/panel/presence"), "ON", "presence on")
    val n1 = mqtt.published.size
    bridge.presence(true)
    eq(mqtt.published.size, n1, "unchanged state is not repeated")
    bridge.presence(false)
    eq(mqtt.last("luxcurve/panel/presence"), "OFF", "presence off")
    bridge.screenOn(false)
    eq(mqtt.last("luxcurve/panel/screen"), "OFF", "screen off")
    bridge.brightness(42)
    eq(mqtt.last("luxcurve/panel/brightness"), "42", "brightness")
    bridge.volume(65)
    eq(mqtt.last("luxcurve/panel/volume"), "65", "volume state")

    // climate appears with the first reading
    bridge.temperature(21.46f)
    check((mqtt.last("homeassistant/sensor/panel/temperature/config") ?: "").startsWith("{"), "temperature entity created")
    check((mqtt.last("homeassistant/sensor/panel/humidity/config") ?: "").startsWith("{"), "humidity entity created")
    eq(mqtt.last("luxcurve/panel/temperature"), "21.5", "temperature")
    bridge.humidity(48.4f)
    eq(mqtt.last("luxcurve/panel/humidity"), "48", "humidity")

    // voice
    bridge.voiceState(VoiceState.LISTENING)
    eq(mqtt.last("luxcurve/panel/voice/state"), "listening", "voice state")
    bridge.voiceCommand("turn on the light")
    bridge.voiceCommand("turn on the light")
    eq(mqtt.published.count { it.first == "luxcurve/panel/voice/command" }, 2, "repeated command is published again")
    bridge.voiceResponse("x".repeat(400))
    check((mqtt.last("luxcurve/panel/voice/response") ?: "").length <= 255, "response fits a sensor state")

    // commands
    bridge.onMessage("luxcurve/panel/volume/set", "50", true)
    eq(actions.log.size, 0, "retained commands are ignored")
    bridge.onMessage("luxcurve/panel/volume/set", "50", false)
    bridge.onMessage("luxcurve/panel/announce/set", "Dinner", false)
    bridge.onMessage("luxcurve/panel/announce/set", """{"message":"Hi","volume":30}""", false)
    bridge.onMessage("luxcurve/panel/play/set", "http://x/a.mp3", false)
    bridge.onMessage("luxcurve/panel/stop/set", "PRESS", false)
    bridge.onMessage("luxcurve/panel/listen/set", "PRESS", false)
    bridge.onMessage("luxcurve/panel/voice/set", "OFF", false)
    bridge.onMessage("luxcurve/panel/volume/set", "garbage", false)
    bridge.onMessage("luxcurve/panel/other", "1", false)
    eq(
        actions.log,
        listOf(
            "volume:50", "announce:Dinner|null|null", "announce:Hi|null|30",
            "announce:null|http://x/a.mp3|null", "stop", "listen", "voice:false",
        ),
        "commands reach the actions",
    )

    // Home Assistant restarts
    val n2 = mqtt.published.size
    bridge.onMessage("homeassistant/status", "offline", false)
    eq(mqtt.published.size, n2, "offline birth message ignored")
    bridge.onMessage("homeassistant/status", "online", false)
    check(mqtt.published.size > n2 + 16, "discovery and state are published again")
    eq(mqtt.last("luxcurve/panel/volume"), "65", "state replayed after the birth message")

    // reconnect replays too
    bridge.onDisconnected()
    val n3 = mqtt.published.size
    bridge.presence(true)
    eq(mqtt.published.size, n3, "nothing is sent while disconnected")
    bridge.onConnected()
    eq(mqtt.last("luxcurve/panel/presence"), "ON", "state that changed while offline is sent after reconnect")

    bridge.close()
    eq(mqtt.published.last().first, "luxcurve/panel/status", "close publishes the status")
    eq(mqtt.published.last().second, "offline", "offline")

    // retire removes everything again
    bridge.onConnected()
    val before = mqtt.published.size
    bridge.retire()
    val retired = mqtt.published.drop(before)
    check(retired.all { it.second == "" && it.third }, "retire only publishes empty retained messages")
    check(retired.any { it.first == "homeassistant/sensor/panel/illuminance/config" }, "entity removed")
    check(retired.any { it.first == "luxcurve/panel/lux" }, "retained state removed")
    check(retired.any { it.first == "homeassistant/sensor/panel/temperature/config" }, "climate entity removed")
    val n5 = mqtt.published.size
    bridge.presence(false)
    eq(mqtt.published.size, n5, "nothing is sent after retire")

    // invalid readings
    val n4 = mqtt.published.size
    bridge.lux(-1f)
    bridge.lux(Float.NaN)
    bridge.temperature(Float.NaN)
    bridge.brightness(-1)
    eq(mqtt.published.size, n4, "invalid readings are dropped")
}

fun testBridgeFeatureSwitch() {
    section("bridge feature switches")
    val mqtt = FakeMqtt()
    val actions = RecordingActions()
    val cfg = HaConfig(mqttEnabled = true, mqttHost = "b", voiceEnabled = false)
    val bridge = HaBridge(cfg, mqtt, INFO, actions) { 0L }
    bridge.onConnected()
    eq(mqtt.last("homeassistant/switch/panel/voice_listening/config"), "", "no voice entities while voice is off")
    bridge.onMessage("luxcurve/panel/listen/set", "PRESS", false)
    eq(actions.log.size, 0, "listen ignored while voice is off")

    bridge.updateConfig(cfg.copy(voiceEnabled = true))
    check((mqtt.last("homeassistant/switch/panel/voice_listening/config") ?: "").startsWith("{"), "voice entities appear")
    bridge.onMessage("luxcurve/panel/listen/set", "PRESS", false)
    eq(actions.log, listOf("listen"), "listen works once voice is on")

    bridge.updateConfig(cfg.copy(voiceEnabled = true, speakerEnabled = false))
    eq(mqtt.last("homeassistant/number/panel/volume/config"), "", "speaker entities removed")
    bridge.onMessage("luxcurve/panel/volume/set", "10", false)
    bridge.onMessage("luxcurve/panel/announce/set", "hi", false)
    eq(actions.log, listOf("listen"), "speaker commands ignored while the speaker is off")
}

fun testAssistEvents() {
    section("assist events")
    fun parse(json: String) = AssistEvents.parse(JsonParser.parseString(json).asJsonObject)
    eq(
        parse("""{"type":"run-start","data":{"pipeline":"01","language":"en","runner_data":{"stt_binary_handler_id":3,"timeout":300}}}"""),
        AssistEvent.RunStart(3, "en"), "run-start",
    )
    eq(parse("""{"type":"run-start","data":{"language":"nl"}}"""), AssistEvent.RunStart(null, "nl"), "run-start without handler")
    eq(
        parse("""{"type":"wake_word-end","data":{"wake_word_output":{"wake_word_id":"okay_nabu","wake_word_phrase":"okay nabu","timestamp":1}}}"""),
        AssistEvent.WakeWordEnd("okay nabu"), "wake_word-end",
    )
    eq(parse("""{"type":"wake_word-end","data":{"wake_word_output":{"wake_word_id":"okay_nabu"}}}"""), AssistEvent.WakeWordEnd("okay_nabu"), "wake word id only")
    eq(parse("""{"type":"stt-end","data":{"stt_output":{"text":"turn on the light"}}}"""), AssistEvent.SttEnd("turn on the light"), "stt-end")
    eq(
        parse(
            """{"type":"intent-end","data":{"processed_locale":"en","intent_output":{"response":{"speech":{"plain":{"speech":"Turned on the light","extra_data":null}},"card":{},"language":"en","response_type":"action_done","data":{}},"conversation_id":"abc","continue_conversation":true}}}""",
        ),
        AssistEvent.IntentEnd("Turned on the light", "abc", true), "intent-end",
    )
    eq(
        parse("""{"type":"intent-end","data":{"intent_output":{"response":{"speech":{"plain":{"speech":"Ok"}}},"conversation_id":null}}}"""),
        AssistEvent.IntentEnd("Ok", null, false), "intent-end with null conversation and no continue flag",
    )
    eq(
        parse("""{"type":"tts-end","data":{"tts_output":{"media_id":"media-source://tts/x","url":"/api/tts_proxy/abc.mp3","mime_type":"audio/mpeg"}}}"""),
        AssistEvent.TtsEnd("/api/tts_proxy/abc.mp3", "audio/mpeg"), "tts-end wrapped",
    )
    eq(parse("""{"type":"tts-end","data":{"url":"/api/tts_proxy/abc.mp3"}}"""), AssistEvent.TtsEnd("/api/tts_proxy/abc.mp3", null), "tts-end flat")
    eq(parse("""{"type":"error","data":{"code":"stt-no-text-recognized","message":"No text"}}"""), AssistEvent.Error("stt-no-text-recognized", "No text"), "error")
    eq(parse("""{"type":"something-new","data":{}}"""), AssistEvent.Other("something-new"), "unknown event")
    eq(parse("""{"type":"run-end"}"""), AssistEvent.RunEnd, "run-end without data")

    val j = JsonParser.parseString(AssistRequest("stt", "tts", conversationId = "c1").toJson(7)).asJsonObject
    eq(j.get("id").asInt, 7, "request id")
    eq(j.get("type").asString, "assist_pipeline/run", "request type")
    eq(j.get("start_stage").asString, "stt", "start stage")
    eq(j.get("end_stage").asString, "tts", "end stage")
    eq(j.getAsJsonObject("input").get("sample_rate").asInt, 16000, "sample rate")
    eq(j.get("conversation_id").asString, "c1", "conversation id")
    val w = JsonParser.parseString(AssistRequest("wake_word", "intent").toJson(1)).asJsonObject
    eq(w.getAsJsonObject("input").get("timeout").asInt, 60, "wake word timeout")
    check(!w.has("conversation_id"), "no conversation id without one")
    val t = JsonParser.parseString(AssistRequest("tts", "tts", text = "Hello \"you\"").toJson(2)).asJsonObject
    eq(t.getAsJsonObject("input").get("text").asString, "Hello \"you\"", "tts text is escaped")
    check(!t.getAsJsonObject("input").has("sample_rate"), "tts has no audio input")
}

class RunRecorder : AssistRun.Listener {
    val log = ArrayList<String>()
    override fun onReadyForAudio() {
        log.add("ready")
    }

    override fun onWakeWord(phrase: String?) {
        log.add("wake:$phrase")
    }

    override fun onSpeechEnd() {
        log.add("speech-end")
    }

    override fun onTranscript(text: String) {
        log.add("text:$text")
    }

    override fun onReply(text: String?, conversationId: String?, continueConversation: Boolean) {
        log.add("reply:$text:$conversationId:$continueConversation")
    }

    override fun onAudio(url: String, mime: String?) {
        log.add("audio:$url")
    }

    override fun onError(code: String, message: String) {
        log.add("error:$code:$message")
    }

    override fun onFinished() {
        log.add("finished")
    }
}

fun testAssistRun() {
    section("assist run")
    val link = FakeLink()
    val rec = RunRecorder()
    val run = AssistRun(link, AssistRequest("stt", "tts"), rec)
    eq(run.id, 1, "first id")
    run.start()
    eq(link.sentText.size, 1, "command sent")
    check(link.handlers.containsKey(1), "handler registered")
    run.audio(byteArrayOf(1, 2, 3))
    run.audio(byteArrayOf(4, 5))
    eq(link.sentBinary.size, 0, "audio waits until Home Assistant is ready")
    link.runStart(1, 9)
    eq(link.sentBinary.size, 0, "run-start alone is not enough for stt")
    link.event(1, "stt-start")
    eq(rec.log, listOf("ready"), "ready")
    eq(link.sentBinary.map { it.toList() }, listOf(listOf<Byte>(9, 1, 2, 3), listOf<Byte>(9, 4, 5)), "buffered audio is framed with the handler id")
    run.audio(byteArrayOf(6))
    eq(link.sentBinary.last().toList(), listOf<Byte>(9, 6), "live audio framed")
    run.finishAudio()
    eq(link.sentBinary.last().toList(), listOf<Byte>(9), "end marker is the handler id alone")
    val n = link.sentBinary.size
    run.audio(byteArrayOf(7))
    run.finishAudio()
    eq(link.sentBinary.size, n, "nothing after the end marker")
    link.event(1, "stt-end", """{"stt_output":{"text":"hello"}}""")
    link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Hi"}}},"conversation_id":"c"}}""")
    link.event(1, "tts-end", """{"tts_output":{"url":"/a.mp3","mime_type":"audio/mpeg"}}""")
    link.event(1, "run-end")
    eq(rec.log, listOf("ready", "text:hello", "reply:Hi:c:false", "audio:/a.mp3", "finished"), "event order")
    check(!link.handlers.containsKey(1), "handler removed at the end")
    link.event(1, "run-end")
    eq(rec.log.count { it == "finished" }, 1, "finished only once")

    // voice activity detection ends the audio
    val link2 = FakeLink()
    val rec2 = RunRecorder()
    val r2 = AssistRun(link2, AssistRequest("stt", "intent"), rec2)
    r2.start()
    link2.runStart(1, 2)
    link2.event(1, "stt-start")
    r2.audio(byteArrayOf(1))
    link2.event(1, "stt-vad-end")
    r2.audio(byteArrayOf(2))
    r2.finishAudio()
    eq(link2.sentBinary.size, 1, "no audio and no end marker after the vad end")
    check("speech-end" in rec2.log, "speech end reported")

    // the pending buffer is capped
    val link3 = FakeLink()
    val r3 = AssistRun(link3, AssistRequest("stt", "intent"), RunRecorder())
    r3.start()
    for (i in 0 until 10) r3.audio(ByteArray(32000) { i.toByte() })
    link3.runStart(1, 1)
    link3.event(1, "stt-start")
    eq(link3.sentBinary.size, 5, "5 seconds of audio are kept")
    eq(link3.sentBinary[0][1], 5.toByte(), "the oldest audio is dropped")

    // errors
    val link4 = FakeLink()
    val rec4 = RunRecorder()
    val r4 = AssistRun(link4, AssistRequest("stt", "tts"), rec4)
    r4.start()
    link4.server(1, """{"id":1,"type":"result","success":false,"error":{"code":"not_found","message":"Pipeline not found"}}""")
    link4.event(1, "run-end")
    eq(rec4.log, listOf("error:not_found:Pipeline not found", "finished"), "result error then finished once")

    val link5 = FakeLink()
    val rec5 = RunRecorder()
    val r5 = AssistRun(link5, AssistRequest("stt", "tts"), rec5)
    r5.start()
    link5.server(1, """{"id":1,"type":"result","success":true,"result":null}""")
    check(rec5.log.isEmpty(), "successful result is not an event")
    link5.event(1, "run-start", """{"language":"en"}""")
    link5.event(1, "stt-start")
    eq(rec5.log, listOf("error:protocol:Home Assistant did not say where to send the audio", "finished"), "missing handler id is an error")

    // cancel
    val link6 = FakeLink()
    val rec6 = RunRecorder()
    val r6 = AssistRun(link6, AssistRequest("stt", "tts"), rec6)
    r6.start()
    link6.runStart(1, 4)
    link6.event(1, "stt-start")
    r6.cancel()
    eq(link6.sentBinary.last().toList(), listOf<Byte>(4), "cancel sends the end marker")
    link6.event(1, "stt-end", """{"stt_output":{"text":"late"}}""")
    eq(rec6.log, listOf("ready"), "nothing is reported after cancel")

    // wake word runs are ready at run-start
    val link7 = FakeLink()
    val rec7 = RunRecorder()
    val r7 = AssistRun(link7, AssistRequest("wake_word", "tts"), rec7)
    r7.start()
    link7.runStart(1, 6)
    eq(rec7.log, listOf("ready"), "wake word run is ready at run-start")
    link7.event(1, "wake_word-end", """{"wake_word_output":{"wake_word_phrase":"okay nabu"}}""")
    check("wake:okay nabu" in rec7.log, "wake word reported")

    // a text-only run takes no audio
    val link8 = FakeLink()
    val r8 = AssistRun(link8, AssistRequest("tts", "tts", text = "hi"), RunRecorder())
    r8.start()
    r8.audio(byteArrayOf(1))
    r8.finishAudio()
    eq(link8.sentBinary.size, 0, "tts run sends no audio")
}

fun testAnnouncer() {
    section("announcer")
    val haCfg = HaConfig(haUrl = "ha:8123", haToken = "t")

    // URL announcements, with and without volume
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd(null, "/local/chime.mp3", 40))
        eq(player.log, listOf("url:http://ha:8123/local/chime.mp3:40"), "url is resolved and played")
        eq(link.sentText.size, 0, "no pipeline for urls")
    }

    // text without Home Assistant: on-device voice
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        val a = Announcer(sched, link, player) { HaConfig() }
        a.enqueue(AnnounceCmd("Dinner", null, 70))
        eq(player.log, listOf("speak:Dinner:70"), "on-device speech without Home Assistant")
        eq(link.connectCalls, 0, "no connection attempt")
    }

    // text with Home Assistant: synthesised there, queue keeps its order
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        player.urlResult = null
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd("One", null, null))
        a.enqueue(AnnounceCmd("Two", null, 20))
        eq(link.sentText.size, 1, "only one synthesis at a time")
        val req = JsonParser.parseString(link.sentText[0]).asJsonObject
        eq(req.get("start_stage").asString, "tts", "tts only")
        eq(req.getAsJsonObject("input").get("text").asString, "One", "text sent")
        link.runStart(1, 0)
        link.event(1, "tts-start")
        link.event(1, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/one.mp3","mime_type":"audio/mpeg"}}""")
        link.event(1, "run-end")
        eq(player.log, listOf("url:http://ha:8123/api/tts_proxy/one.mp3:null"), "first announcement plays")
        eq(link.sentText.size, 1, "second waits for the first")
        player.finish(true)
        eq(link.sentText.size, 2, "second starts after the first")
        link.event(2, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/two.mp3"}}""")
        eq(player.log.last(), "url:http://ha:8123/api/tts_proxy/two.mp3:20", "second plays with its own volume")
        player.finish(true)
        eq(link.handlers.size, 0, "no handlers left")
        eq(sched.pendingCount() >= 0, true, "timers harmless")
    }

    // synthesis error: fall back to the on-device voice
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd("Hi", null, null))
        link.event(1, "error", """{"code":"tts-failed","message":"no"}""")
        eq(player.log, listOf("speak:Hi:null"), "speaks on the device after an error")
        link.event(1, "run-end")
        eq(player.log.size, 1, "only once")
    }

    // connection failure
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        link.failReason = "no route"
        val player = FakePlayer()
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd("Hi", null, null))
        eq(player.log, listOf("speak:Hi:null"), "speaks on the device without a connection")
    }

    // audio that cannot be played
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        player.urlResult = false
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd("Hi", null, null))
        link.event(1, "tts-end", """{"tts_output":{"url":"/a.mp3"}}""")
        eq(player.log, listOf("url:http://ha:8123/a.mp3:null", "speak:Hi:null"), "on-device voice when the audio fails")
    }

    // synthesis never answers
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd("Hi", null, null))
        sched.advance(19_000)
        eq(player.log.size, 0, "still waiting")
        sched.advance(2_000)
        eq(player.log, listOf("speak:Hi:null"), "timeout falls back")
        eq(link.handlers.size, 0, "run cancelled")
    }

    // stop clears the queue and ignores late results
    run {
        val sched = FakeScheduler()
        val link = FakeLink()
        val player = FakePlayer()
        player.urlResult = null
        val a = Announcer(sched, link, player) { haCfg }
        a.enqueue(AnnounceCmd(null, "/a.mp3", null))
        a.enqueue(AnnounceCmd(null, "/b.mp3", null))
        a.stop()
        eq(player.log, listOf("url:http://ha:8123/a.mp3:null", "stop"), "stop stops the player and drops the queue")
        a.enqueue(AnnounceCmd(null, "/c.mp3", null))
        eq(player.log.last(), "url:http://ha:8123/c.mp3:null", "works again after stop")
    }
}

class World(cfg: HaConfig) {
    val sched = FakeScheduler()
    val link = FakeLink()
    val mic = FakeMic()
    val player = FakePlayer()
    val out = FakeOut()
    val engine = VoiceEngine(sched, link, mic, player, out, cfg)

    fun start() {
        engine.start()
        sched.idle()
    }

    fun idle() = sched.idle()

    /** Walks a started talk session up to the point where Home Assistant listens. */
    fun ready(id: Int = 1, handler: Int = 1) {
        link.runStart(id, handler)
        link.event(id, "stt-start")
        idle()
    }

    fun json(i: Int) = JsonParser.parseString(link.sentText[i]).asJsonObject
}

fun testVoicePushToTalk() {
    section("voice: push to talk")
    val w = World(voiceCfg())
    w.start()
    eq(w.out.states, listOf("idle"), "idle after start")
    check(!w.mic.running, "microphone closed while idle")
    w.player.urlResult = null
    w.engine.listen()
    w.idle()
    eq(w.link.sentText.size, 1, "run started")
    eq(w.json(0).get("start_stage").asString, "stt", "starts at stt")
    eq(w.json(0).get("end_stage").asString, "tts", "ends at tts")
    check(w.mic.running, "microphone opened")
    w.ready()
    eq(w.out.last, "listening", "listening")
    w.mic.chunk(loud())
    w.idle()
    eq(w.link.sentBinary.last()[0], 1.toByte(), "audio goes out with the handler id")
    eq(w.link.sentBinary.last().size, 641, "frame size")
    w.link.event(1, "stt-vad-start")
    w.link.event(1, "stt-vad-end")
    w.idle()
    eq(w.out.last, "processing", "processing")
    check(!w.mic.running, "microphone closed after the speech")
    w.link.event(1, "stt-end", """{"stt_output":{"text":"turn on the light"}}""")
    w.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Done"}}},"conversation_id":"c1","continue_conversation":false}}""")
    w.link.event(1, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/x.mp3"}}""")
    w.link.event(1, "run-end")
    w.idle()
    eq(w.out.commands, listOf("turn on the light"), "command published")
    eq(w.out.responses, listOf("Done"), "response published")
    eq(w.out.last, "speaking", "speaking")
    eq(w.player.log, listOf("url:http://ha:8123/api/tts_proxy/x.mp3:null"), "reply played")
    w.player.finish(true)
    w.idle()
    eq(w.out.states, listOf("idle", "listening", "processing", "speaking", "idle"), "state sequence")
    eq(w.link.handlers.size, 0, "no handler left")
    eq(w.out.detail, null, "no problem reported")

    // the microphone is ignored while the speaker plays (announcement during a command)
    val w2 = World(voiceCfg())
    w2.start()
    w2.engine.listen()
    w2.idle()
    w2.ready()
    w2.player.urlResult = null
    w2.player.playUrl("x", null) { }
    w2.mic.chunk(loud())
    w2.idle()
    eq(w2.link.sentBinary.size, 0, "no audio while the panel is playing something")
    w2.player.finish(true)
    w2.mic.chunk(loud())
    w2.idle()
    eq(w2.link.sentBinary.size, 1, "audio again afterwards")

    // reply without speech output
    val w3 = World(voiceCfg({ it.copy(voiceSpeak = false) }))
    w3.start()
    w3.engine.listen()
    w3.idle()
    eq(w3.json(0).get("end_stage").asString, "intent", "no tts stage when speaking is off")
    w3.ready()
    w3.link.event(1, "stt-end", """{"stt_output":{"text":"hi"}}""")
    w3.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Hello"}}}}}""")
    w3.link.event(1, "run-end")
    w3.idle()
    eq(w3.player.log.size, 0, "nothing is played")
    eq(w3.out.last, "idle", "back to idle")
    eq(w3.out.responses, listOf("Hello"), "text response still published")

    // mic gain
    val w4 = World(voiceCfg({ it.copy(micGain = 2f) }))
    w4.start()
    w4.engine.listen()
    w4.idle()
    w4.ready()
    w4.mic.chunk(pcm(1000, -1000))
    w4.idle()
    eq(w4.link.sentBinary.last().toList(), listOf<Byte>(1) + pcm(2000, -2000).toList(), "gain applied")
}

fun testVoiceWake() {
    section("voice: wake word")
    val w = World(voiceCfg({ it.copy(voiceWake = true) }))
    w.start()
    eq(w.link.sentText.size, 1, "wake run started")
    eq(w.json(0).get("start_stage").asString, "wake_word", "wake word stage")
    check(w.mic.running, "microphone streams")
    w.link.runStart(1, 2)
    w.idle()
    eq(w.out.last, "wake_word", "waiting for the wake word")
    w.player.urlResult = null
    w.mic.chunk(loud())
    w.idle()
    eq(w.link.sentBinary.last()[0], 2.toByte(), "audio streams")
    w.link.event(1, "wake_word-start")
    w.link.event(1, "wake_word-end", """{"wake_word_output":{"wake_word_phrase":"okay nabu"}}""")
    w.idle()
    eq(w.out.last, "listening", "listening after the wake word")
    w.link.event(1, "stt-start")
    w.link.event(1, "stt-vad-end")
    w.idle()
    eq(w.out.last, "processing", "processing")
    check(w.mic.running, "the microphone stays on in wake word mode")
    w.link.event(1, "stt-end", """{"stt_output":{"text":"lights off"}}""")
    w.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Ok"}}}}}""")
    w.link.event(1, "tts-end", """{"tts_output":{"url":"/a.mp3"}}""")
    w.link.event(1, "run-end")
    w.idle()
    eq(w.out.last, "speaking", "speaking")
    eq(w.link.sentText.size, 1, "waits for the speech before listening again")
    w.player.finish(true)
    w.idle()
    eq(w.link.sentText.size, 2, "next wake run")
    eq(w.json(1).get("start_stage").asString, "wake_word", "wake word again")
    eq(w.mic.startCount, 1, "microphone opened once")
    eq(w.mic.stopCount, 0, "microphone never closed")
    eq(w.out.commands, listOf("lights off"), "command")

    // the wait for the wake word times out: quietly start the next run
    w.link.runStart(2, 3)
    w.link.event(2, "error", """{"code":"wake-word-timeout","message":"Wake word timeout"}""")
    w.idle()
    eq(w.link.sentText.size, 3, "restarted after the timeout")
    eq(w.out.last, "wake_word", "no error state for a timeout")
    eq(w.out.detail, null, "no problem for a timeout")

    // listening switched off in Home Assistant
    w.engine.updateConfig(voiceCfg({ it.copy(voiceWake = true, voiceActive = false) }))
    w.idle()
    eq(w.out.last, "disabled", "disabled")
    check(!w.mic.running, "microphone closed")
    eq(w.link.handlers.size, 0, "run cancelled")
    eq(w.out.detail, "Voice listening is switched off", "reason shown")
    w.engine.updateConfig(voiceCfg({ it.copy(voiceWake = true, voiceActive = true) }))
    w.idle()
    check(w.mic.running, "microphone open again")
    eq(w.link.sentText.size, 4, "wake run again")

    // switching to push to talk
    w.engine.updateConfig(voiceCfg({ it.copy(voiceWake = false) }))
    w.idle()
    eq(w.out.last, "idle", "idle")
    check(!w.mic.running, "microphone closed in push to talk mode")

    // an explicit listen in wake word mode
    val w2 = World(voiceCfg({ it.copy(voiceWake = true) }))
    w2.start()
    w2.link.runStart(1, 1)
    w2.engine.listen()
    w2.idle()
    eq(w2.link.sentText.size, 2, "listen replaces the wake run")
    eq(w2.json(1).get("start_stage").asString, "stt", "command run")
    eq(w2.mic.startCount, 1, "same microphone session")
    w2.link.event(2, "error", """{"code":"stt-no-text-recognized","message":"none"}""")
    w2.idle()
    eq(w2.link.sentText.size, 3, "back to the wake word")
    eq(w2.json(2).get("start_stage").asString, "wake_word", "wake word again")
    eq(w2.out.detail, "No speech recognised", "note shown")
}

fun testVoiceFollowUp() {
    section("voice: follow-up questions")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready(1, 1)
    w.link.event(1, "stt-end", """{"stt_output":{"text":"set a timer"}}""")
    w.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"For how long?"}}},"conversation_id":"c1","continue_conversation":true}}""")
    w.link.event(1, "tts-end", """{"tts_output":{"url":"/q.mp3"}}""")
    w.link.event(1, "run-end")
    w.idle()
    eq(w.link.sentText.size, 2, "listens again after the question")
    eq(w.json(1).get("conversation_id").asString, "c1", "same conversation")
    eq(w.json(1).get("start_stage").asString, "stt", "command run")
    check(w.mic.running, "microphone open again")

    // an endless conversation is cut off after 3 follow-ups
    val w2 = World(voiceCfg())
    w2.start()
    w2.engine.listen()
    w2.idle()
    for (i in 1..6) {
        if (w2.link.sentText.size < i) break
        w2.ready(i, 1)
        w2.link.event(i, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"More?"}}},"conversation_id":"c","continue_conversation":true}}""")
        w2.link.event(i, "tts-end", """{"tts_output":{"url":"/q.mp3"}}""")
        w2.link.event(i, "run-end")
        w2.idle()
    }
    eq(w2.link.sentText.size, 4, "one command plus three follow-ups")
    eq(w2.out.last, "idle", "idle at the end")
}

fun testVoiceErrors() {
    section("voice: errors")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready()
    w.link.event(1, "error", """{"code":"intent-failed","message":"Intent failed"}""")
    w.idle()
    eq(w.out.last, "error", "error state")
    eq(w.out.detail, "Intent failed", "error detail")
    check(!w.mic.running, "microphone closed after an error")
    w.sched.advance(2_000)
    eq(w.out.last, "idle", "error clears")
    w.engine.listen()
    w.idle()
    eq(w.link.sentText.size, 2, "still usable")
    w.ready(2, 1)
    w.link.event(2, "stt-end", """{"stt_output":{"text":"x"}}""")
    w.link.event(2, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Ok"}}}}}""")
    w.link.event(2, "tts-end", """{"tts_output":{"url":"/a.mp3"}}""")
    w.link.event(2, "run-end")
    w.idle()
    eq(w.out.detail, null, "problem cleared after a good run")

    // lost connection
    val w2 = World(voiceCfg())
    w2.start()
    w2.engine.listen()
    w2.idle()
    w2.ready()
    w2.link.server(1, """{"id":1,"type":"result","success":false,"error":{"code":"connection_lost","message":"Connection to Home Assistant lost"}}""")
    w2.idle()
    eq(w2.out.last, "error", "error after a lost connection")
    eq(w2.out.detail, "Connection to Home Assistant lost", "detail")

    // listen while voice is off
    val w3 = World(voiceCfg({ it.copy(voiceActive = false) }))
    w3.start()
    eq(w3.out.last, "disabled", "disabled")
    w3.engine.listen()
    w3.idle()
    eq(w3.link.sentText.size, 0, "no run while voice is off")
    eq(w3.out.detail, "Voice listening is switched off", "reason")

    // no token
    val w4 = World(HaConfig(voiceEnabled = true, haUrl = "ha:8123"))
    w4.start()
    eq(w4.out.last, "disabled", "disabled without a token")
    eq(w4.out.detail, "Enter the Home Assistant address and access token", "reason without token")
}

fun testVoiceRetry() {
    section("voice: retry")
    val w = World(voiceCfg({ it.copy(voiceWake = true) }))
    w.link.failReason = "Cannot connect"
    w.start()
    eq(w.link.connectCalls, 1, "first attempt")
    eq(w.out.last, "error", "error")
    eq(w.out.detail, "Cannot connect", "detail")
    w.sched.advance(1_900)
    eq(w.link.connectCalls, 1, "waits 2 seconds")
    w.sched.advance(200)
    eq(w.link.connectCalls, 2, "second attempt")
    w.sched.advance(4_000)
    eq(w.link.connectCalls, 3, "third attempt after 4 seconds")
    w.sched.advance(8_000)
    eq(w.link.connectCalls, 4, "fourth attempt after 8 seconds")
    w.sched.advance(16_000)
    w.sched.advance(32_000)
    w.sched.advance(60_000)
    w.sched.advance(60_000)
    eq(w.link.connectCalls, 8, "backoff stops growing at 60 seconds")

    // the connection comes back
    w.link.failReason = null
    w.sched.advance(60_000)
    w.link.runStart(1, 1)
    w.idle()
    eq(w.out.last, "wake_word", "recovered")
    eq(w.out.detail, null, "problem cleared")

    // a rejected token is not retried
    val w2 = World(voiceCfg({ it.copy(voiceWake = true) }))
    w2.link.failReason = "Home Assistant rejected the access token"
    w2.link.failedPermanently = true
    w2.start()
    w2.sched.advance(600_000)
    eq(w2.link.connectCalls, 1, "no retries after a permanent failure")
    eq(w2.out.last, "error", "stays in error")
    w2.link.failReason = null
    w2.link.failedPermanently = false
    w2.engine.updateConfig(voiceCfg({ it.copy(voiceWake = true, haToken = "new") }))
    w2.idle()
    eq(w2.link.connectCalls, 2, "a new token is tried at once")

    // microphone cannot be opened
    val w3 = World(voiceCfg({ it.copy(voiceWake = true) }))
    w3.mic.problem = "Permission denied"
    w3.start()
    eq(w3.out.last, "error", "error without a microphone")
    eq(w3.out.detail, "Microphone: Permission denied", "microphone detail")
    eq(w3.link.handlers.size, 0, "run cancelled")
    w3.mic.problem = null
    w3.sched.advance(2_100)
    check(w3.mic.running, "microphone opened on retry")
}

fun testVoiceConfigChanges() {
    section("voice: config changes")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready()
    // a change that does not matter keeps the session alive
    w.engine.updateConfig(voiceCfg({ it.copy(micGain = 1.5f, voiceApproach = true) }))
    w.idle()
    eq(w.link.sentText.size, 1, "session kept")
    check(w.mic.running, "microphone kept")
    // another Home Assistant address restarts
    w.engine.updateConfig(voiceCfg({ it.copy(micGain = 1.5f, haUrl = "other:8123") }))
    w.idle()
    eq(w.out.last, "idle", "idle after a change of address")
    eq(w.link.handlers.size, 0, "session cancelled")
    check(!w.mic.running, "microphone closed")
    // stop
    w.engine.stop()
    w.idle()
    eq(w.out.last, "disabled", "disabled after stop")
    w.engine.listen()
    w.idle()
    eq(w.link.sentText.size, 1, "nothing after stop")
}

fun testVoiceApproach() {
    section("voice: approach")
    val w = World(voiceCfg({ it.copy(voiceApproach = true) }))
    w.start()
    w.engine.onPresence(false)
    w.idle()
    eq(w.link.sentText.size, 0, "nobody there")
    w.engine.onPresence(true)
    w.idle()
    eq(w.link.sentText.size, 1, "walking up starts a command")
    w.engine.onPresence(true)
    w.idle()
    eq(w.link.sentText.size, 1, "still standing there: no second command")
    w.ready()
    w.link.event(1, "error", """{"code":"stt-no-text-recognized","message":"none"}""")
    w.idle()
    w.engine.onPresence(false)
    w.engine.onPresence(true)
    w.idle()
    eq(w.link.sentText.size, 1, "cooldown")
    w.sched.advance(10_000)
    w.engine.onPresence(false)
    w.engine.onPresence(true)
    w.idle()
    eq(w.link.sentText.size, 2, "after the cooldown")

    val off = World(voiceCfg())
    off.start()
    off.engine.onPresence(false)
    off.engine.onPresence(true)
    off.idle()
    eq(off.link.sentText.size, 0, "approach is off by default")

    val busy = World(voiceCfg({ it.copy(voiceApproach = true) }))
    busy.start()
    busy.player.urlResult = null
    busy.player.playUrl("x", null) { }
    busy.engine.onPresence(false)
    busy.engine.onPresence(true)
    busy.idle()
    eq(busy.link.sentText.size, 0, "not while something is playing")

    // wake word mode: approaching starts a command straight away
    val ww = World(voiceCfg({ it.copy(voiceApproach = true, voiceWake = true) }))
    ww.start()
    ww.link.runStart(1, 1)
    ww.engine.onPresence(false)
    ww.engine.onPresence(true)
    ww.idle()
    eq(ww.link.sentText.size, 2, "wake run replaced by a command")
    eq(ww.json(1).get("start_stage").asString, "stt", "command")
}

fun testVoiceMic() {
    section("voice: microphone")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready()
    val zeros = ByteArray(2000)
    for (i in 0 until 40) w.mic.chunk(zeros)
    w.idle()
    eq(w.out.detail, null, "80000 bytes of silence is not yet a warning")
    for (i in 0 until 10) w.mic.chunk(zeros)
    w.idle()
    eq(w.out.detail, VoiceEngine.SILENT_MESSAGE, "silent microphone is reported")
    w.mic.chunk(loud())
    w.idle()
    eq(w.out.detail, null, "warning clears when sound returns")

    // a runtime failure of the microphone
    w.mic.listener?.onProblem("read failed")
    w.idle()
    eq(w.out.last, "error", "error")
    eq(w.out.detail, "Microphone: read failed", "detail")
    w.mic.listener?.onProblem("again")
    w.idle()
    eq(w.out.detail, "Microphone: read failed", "late problems are ignored once the microphone is closed")
}

fun testVoiceTimers() {
    section("voice: timers")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready(1, 5)
    w.sched.advance(19_000)
    val before = w.link.sentBinary.size
    w.sched.advance(2_000)
    eq(w.link.sentBinary.size, before + 1, "end marker after 20 seconds")
    eq(w.link.sentBinary.last().toList(), listOf<Byte>(5), "end marker content")
    w.sched.advance(40_000)
    eq(w.out.last, "error", "error when Home Assistant never answers")
    eq(w.out.detail, "Home Assistant did not answer in time", "detail")
    check(!w.mic.running, "microphone closed")
    w.sched.advance(2_100)
    eq(w.out.last, "idle", "idle again")

    // a normal run leaves no timers behind
    val w2 = World(voiceCfg())
    w2.start()
    w2.engine.listen()
    w2.idle()
    w2.ready()
    w2.link.event(1, "stt-end", """{"stt_output":{"text":"x"}}""")
    w2.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Ok"}}}}}""")
    w2.link.event(1, "run-end")
    w2.idle()
    eq(w2.sched.pendingCount(), 0, "no timers pending")
}

fun testVoiceNoSpeech() {
    section("voice: nothing said")
    val w = World(voiceCfg())
    w.start()
    w.engine.listen()
    w.idle()
    w.ready()
    w.link.event(1, "error", """{"code":"stt-no-text-recognized","message":"No text recognized"}""")
    w.idle()
    eq(w.out.last, "idle", "idle, not an error")
    eq(w.out.detail, "No speech recognised", "note")
    check(!w.mic.running, "microphone closed")
    eq(w.sched.pendingCount(), 0, "no retry timer")
}

fun testVoiceFallbackPlayback() {
    section("voice: reply playback")
    val w = World(voiceCfg())
    w.player.urlResult = false
    w.start()
    w.engine.listen()
    w.idle()
    w.ready()
    w.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Hello there"}}}}}""")
    w.link.event(1, "tts-end", """{"tts_output":{"url":"/a.mp3"}}""")
    w.link.event(1, "run-end")
    w.idle()
    eq(w.player.log, listOf("url:http://ha:8123/a.mp3:null", "speak:Hello there:null"), "on-device voice when the audio fails")
    eq(w.out.last, "idle", "idle afterwards")

    // no audio from Home Assistant but a text reply
    val w2 = World(voiceCfg())
    w2.start()
    w2.engine.listen()
    w2.idle()
    w2.ready()
    w2.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Text only"}}}}}""")
    w2.link.event(1, "run-end")
    w2.idle()
    eq(w2.player.log, listOf("speak:Text only:null"), "text reply is spoken on the device")

    // the stop button silences the reply and nothing is spoken on the device instead
    val w5 = World(voiceCfg())
    w5.player.urlResult = null
    w5.start()
    w5.engine.listen()
    w5.idle()
    w5.ready()
    w5.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Long answer"}}}}}""")
    w5.link.event(1, "tts-end", """{"tts_output":{"url":"/long.mp3"}}""")
    w5.link.event(1, "run-end")
    w5.idle()
    eq(w5.out.last, "speaking", "speaking before the stop")
    w5.engine.stopSpeaking()
    w5.idle()
    eq(w5.out.last, "idle", "idle after the stop")
    eq(w5.player.log, listOf("url:http://ha:8123/long.mp3:null", "stop"), "reply stopped, no fallback speech")

    // listen pressed while the reply plays: the reply stops and a new command starts
    val w3 = World(voiceCfg())
    w3.player.urlResult = null
    w3.start()
    w3.engine.listen()
    w3.idle()
    w3.ready()
    w3.link.event(1, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Long answer"}}}}}""")
    w3.link.event(1, "tts-end", """{"tts_output":{"url":"/long.mp3"}}""")
    w3.link.event(1, "run-end")
    w3.idle()
    eq(w3.out.last, "speaking", "speaking")
    w3.engine.listen()
    w3.idle()
    eq(w3.link.sentText.size, 2, "new command started")
    eq(w3.out.last, "listening", "listening right away")
    w3.ready(2, 1)
    eq(w3.out.last, "listening", "listening again")
    check(w3.player.log.contains("stop"), "the reply was stopped")
}
