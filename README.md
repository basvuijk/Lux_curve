# Lux Curve

Small Android app (Kotlin, Material 3 design: large title, grouped rounded cards, sliders, dynamic color,
dark mode, edge-to-edge) that

1. reads the ambient light sensor and sets the screen brightness through a **curve you draw**,
2. turns the screen off after a **user-defined idle time**,
3. turns it back on when the **proximity sensor** reports something at or nearer than a **user-defined distance**
   (or, in radar mode, whenever a radar sensor reports somebody is there),
4. shows the **temperature and humidity** from the chip of a wall panel, when the device has one,
5. connects the panel to **Home Assistant**: light, proximity and screen state as sensors, **announcements
   and speaker volume** from automations, and the **microphone as a voice command input** (Assist).

## Build

Open the `LuxCurve` folder in Android Studio (Koala or newer) and press Run. Gradle 8.7, AGP 8.5.2,
Kotlin 1.9.24, minSdk 26, targetSdk 34. The Gradle wrapper jar is not included; Android Studio downloads
Gradle from `gradle/wrapper/gradle-wrapper.properties` by itself. For command line builds run
`gradle wrapper` once, then `./gradlew assembleDebug`.

## First start

Switch on **Run brightness service** and grant what the buttons ask for:

| Permission | Needed for |
|---|---|
| Modify system settings | Writing the screen brightness (curve) |
| Display over other apps | Black "screen off" overlay, and noticing touches in other apps (idle detection) |
| Device admin (optional) | A real screen off via `lockNow()` instead of the black overlay |
| Notifications (Android 13+) | The foreground service notification |

## How it works

* **Curve**: 7 points at fixed, log-spaced light levels (1, 5, 21, 100, 464, 2154, 10000 lux). Drag a point
  up or down to set the brightness (0-100 %) at that light level; values in between are interpolated on the
  log axis. The light reading is smoothed (about 2 s) so the screen does not flicker. Brightness is written to
  the system setting `SCREEN_BRIGHTNESS` (minimum level 1/255, auto-brightness is switched to manual).
* **Idle time**: restarted by taps anywhere (an invisible 1x1 overlay receives `ACTION_OUTSIDE` events), by
  the screen turning on, and, if "wake on proximity" is on, by something being near the sensor.
  When it expires the screen goes off in one of two ways:
  * default: a black full-screen overlay with window brightness 0 (no lock, tap to wake). On LCD panels
    brightness 0 is the dimmest level the panel allows, which is not always completely dark;
  * "Really switch off": `DevicePolicyManager.lockNow()`, the screen is fully off and the device locks.
* **Proximity wake**: "near" means the sensor reading is at most the value you enter (cm) and below the
  sensor's maximum range. Many phones only report 0 (near) or the maximum (far), so any value between
  works there. The live reading is shown at the top of the screen. While the screen is off a partial wake
  lock keeps the CPU awake so the sensor keeps reporting.
* **Radar presence mode** (Wake up card): a radar sensor that Android reports as the proximity sensor usually
  gives 0 when nobody is there and something else when somebody is. With this switch on, any reading other
  than 0 wakes the screen and the distance slider is ignored. Leave it off for an ordinary proximity sensor.
* **Temperature and humidity** (wall panels): read every 10 seconds from the panel's I2C chip through
  `/sys/devices/platform/twi.1/i2c-1/1-0040/temp1_input` and `humidity1_input` (values in thousandths) and
  shown in the status card. Other devices do not have these files; the screen then says why it could not
  read them.
* The service restarts after a reboot if it was switched on.

## Home Assistant

Open **Home Assistant** on the main screen. There are three parts, each can be used on its own.

### 1. Sensors and speaker over MQTT

Needs an MQTT broker that Home Assistant also uses, for example the *Mosquitto broker* add-on plus the
*MQTT* integration. In the app switch on **Publish to MQTT**, fill in the broker address (use the IP
address, names ending in `.local` often do not resolve on Android), port, user and password, and press
**Save and apply**. The panel then appears in Home Assistant as one device with these entities:

| Entity | Type | Notes |
|---|---|---|
| Illuminance | sensor, lx | light sensor reading, sent when it changes noticeably and at least every 60 s |
| Proximity | sensor, cm | raw proximity reading (radar mode: 0 or the radar's value) |
| Presence | binary sensor | on when the proximity sensor sees someone (same rule as the wake-up) |
| Screen brightness | sensor, % | the brightness the curve has set |
| Screen | binary sensor | on while the screen is on |
| Temperature, Humidity | sensors | only on panels with the chip, and only while the brightness service runs |
| Speaker volume | number, 0-100 % | media volume of the panel, two-way |
| Announce | notify | text to speak, see below |
| Play URL | text | address of an audio file to play |
| Stop audio | button | stops what is playing and empties the queue |
| Voice listening | switch | pauses and resumes the microphone from Home Assistant |
| Listen | button | starts one voice command |
| Voice status | sensor | disabled, idle, wake_word, listening, processing, speaking, error |
| Last voice command, Last voice response | sensors | what was understood and what Home Assistant answered |

The entities are created with MQTT discovery, they go *unavailable* when the app stops and are removed again
when you switch the feature off or change the node id. Give every panel its own **node id**; it is part of
the topics (`luxcurve/<node id>/...`) and of the entity ids.

### 2. Announcements and speaker volume

* `number.set_value` on **Speaker volume** sets the media volume. The same slider follows the volume buttons of the panel.
* `notify.send_message` on **Announce** speaks a text. With the Home Assistant address and token filled in
  (see voice below) the text is turned into speech by the text-to-speech of Home Assistant, so it sounds like
  your other assistants. Without them, or when Home Assistant does not answer, the voice of the Android
  device itself is used.
* `text.set_value` on **Play URL** plays an audio file (`http://...mp3`). A path that starts with `/` is
  taken relative to the Home Assistant address, so `/local/chime.mp3` and `/api/tts_proxy/...` work.
* Announcements are queued and played one after the other; **Stop audio** clears them.
* Only the plain MQTT topics give control over the volume of one announcement. Publish JSON to
  `luxcurve/<node id>/announce/set`:

```yaml
automation:
  - alias: Doorbell on the wall panel
    triggers:
      - trigger: state
        entity_id: binary_sensor.front_door_bell
        to: "on"
    actions:
      - action: mqtt.publish
        data:
          topic: luxcurve/panel/announce/set
          payload: '{"message": "Someone is at the front door", "volume": 70}'
      # or a sound: '{"url": "/local/chime.mp3", "volume": 50}'
```

The volume of such an announcement is restored afterwards. Retained messages are ignored, so an old
announcement does not play again when the app restarts.

### 3. Voice commands with Assist

The microphone is streamed (16 kHz, mono) over the WebSocket API of Home Assistant to your **preferred
voice assistant** (Settings, Voice assistants). It does the speech-to-text, runs the command, and its
answer is played on the panel. For this the app needs:

1. the **Home Assistant address**, as an IP address with port, for example `192.168.1.20:8123`
   (or a full `http://` / `https://` address);
2. a **long-lived access token**: in Home Assistant open your profile, Security, *Create token*, and paste it
   in the app. The token is typed in the app only, never put it in a message or a repository;
3. the **microphone permission** (the row in the voice card).

A command starts in one of three ways:

* the **Listen** button in Home Assistant (or *Listen now* in the app), from any automation;
* **Listen when somebody walks up**: the proximity sensor sees someone;
* **Listen for a wake word**: the microphone stays open and Home Assistant listens for the wake word. The
  voice assistant must have a *wake word* engine selected (for example the openWakeWord add-on). The audio
  only goes to your own Home Assistant, and it is not sent while the panel itself is speaking.

When the answer asks a question (a conversation that continues), the panel listens again by itself, up to
three times in a row. **Voice listening** off stops all of it, for example when you want some privacy.
**Microphone gain** helps with a quiet panel microphone.

### Keeping it running

The link runs in its own foreground service with a notification, started with the app and again after a
reboot. **Stay connected with the screen off** keeps a CPU and a Wi-Fi lock so the panel stays reachable
while the screen is dark; it is meant for a panel on mains power.

Known limits of this part:

* Written and tested against simulated brokers and a simulated Home Assistant, **not yet against real ones**
  or a real microphone. The first run on the panel is the real test, please report what you see in the status
  card at the top of the Home Assistant screen.
* From **Android 14** an app may only start a microphone service while it is on screen. After a reboot the
  sensors and announcements start by themselves, the voice part says *open the app once*; opening the Lux
  Curve app (not just the Home Assistant screen) is enough.
* The app allows plain `http` and `ws` because Home Assistant and the broker normally run without TLS on the
  home network. The *Encrypted connection* switch is for a broker with TLS; its certificate has to be one that
  Android trusts, a self-signed one is refused. App backup is switched off so the token and password do not
  end up in a cloud backup.
* Some panels have no microphone, or one that Android does not expose; the status card then says so.

## Known limitations

* Only the start of a touch counts as activity in other apps (Android does not give an overlay more).
* Another app, or the system auto-brightness, can change the brightness too; the curve is re-applied on the next light change.
* Waking with the wake lock does not dismiss a secure lock screen.
* Not tested on a device by the author of this code: please report build errors and anything odd.
  The build runs on every push (GitHub Actions, `assembleDebug`).
