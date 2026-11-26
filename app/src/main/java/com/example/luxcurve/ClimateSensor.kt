package com.example.luxcurve

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Temperature and humidity chip of the wall panel: an I2C sensor that Linux exposes as two small files.
 * Both hold thousandths, so 21500 means 21.5 degrees C and 45000 means 45 % relative humidity.
 *
 * Other devices (a normal phone, for example) do not have these files. Every reading then comes back
 * with a null [Reading.value] and a short reason in [Reading.error].
 */
class ClimateSensor(private val dir: String = DEFAULT_DIR) {

    /** [value] is null when the sensor could not be read; [error] then says why. */
    class Reading(val value: Float?, val error: String?)

    /** Degrees Celsius. */
    fun temperature(): Reading = read("temp1_input")

    /** Percent relative humidity. */
    fun humidity(): Reading = read("humidity1_input")

    private fun read(name: String): Reading {
        val path = "$dir/$name"
        var problem = "no data"
        try {
            return parse(File(path).readText(), path)
        } catch (e: IOException) {
            problem = e.message ?: problem
        }
        // Not readable straight away. The vendor example goes through a shell (`cat`), so try that
        // as well, unless the file is simply not there.
        if (!problem.contains("No such file", ignoreCase = true)) {
            val text = shell("cat $path")
            if (text != null) return parse(text, path)
        }
        return Reading(null, problem)
    }

    private fun parse(text: String, path: String): Reading {
        val thousandths = text.trim().toFloatOrNull()
            ?: return Reading(null, "unexpected content in $path")
        return Reading(thousandths / 1000f, null)
    }

    /** Runs a shell command and returns what it printed, or null if it failed, printed nothing or hung. */
    private fun shell(command: String): String? {
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) return null
            val out = process.inputStream.bufferedReader().use { it.readText() }
            return if (out.isBlank()) null else out
        } catch (e: Exception) {
            return null
        } finally {
            process?.destroy()
        }
    }

    companion object {
        const val DEFAULT_DIR = "/sys/devices/platform/twi.1/i2c-1/1-0040"

        /** How often the service reads the sensor, and how long it waits before retrying after a failure. */
        const val POLL_MS = 10_000L
        const val RETRY_MS = 60_000L
    }
}
