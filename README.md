# Lux Curve

Small Android app (Kotlin, Material 3 design: large title, grouped rounded cards, sliders, dynamic color,
dark mode, edge-to-edge) that

1. reads the ambient light sensor and sets the screen brightness through a **curve you draw**,
2. turns the screen off after a **user-defined idle time**,
3. turns it back on when the **proximity sensor** reports something at or nearer than a **user-defined distance**
   (or, in radar mode, whenever a radar sensor reports somebody is there),
4. shows the **temperature and humidity** from the chip of a wall panel, when the device has one.

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

## Known limitations

* Only the start of a touch counts as activity in other apps (Android does not give an overlay more).
* Another app, or the system auto-brightness, can change the brightness too; the curve is re-applied on the next light change.
* Waking with the wake lock does not dismiss a secure lock screen.
* Not compiled or tested on a device by the author of this code: please report build errors.
