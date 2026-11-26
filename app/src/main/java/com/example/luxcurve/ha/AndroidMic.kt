package com.example.luxcurve.ha

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The panel's microphone as 16 kHz mono 16-bit PCM in chunks of 40 ms. The chunks arrive on a thread
 * of their own, every chunk in an array of its own.
 */
class AndroidMic(context: Context) : MicSource {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(listener: MicSource.Listener): String? {
        if (running) return null
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "microphone permission not granted"
        }
        // The previous reader may still be shutting down.
        thread?.join(500)
        val rate = Pcm.SAMPLE_RATE
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return "this device cannot record 16 kHz audio"
        val chunk = rate / 1000 * CHUNK_MS * 2
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, chunk * 4),
            )
        } catch (e: Exception) {
            return "cannot open the microphone: " + (e.message ?: e.javaClass.simpleName)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "the microphone could not be initialised"
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return "cannot start recording: " + (e.message ?: e.javaClass.simpleName)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return "the microphone is in use by another app"
        }
        running = true
        val t = Thread({ read(record, chunk, listener) }, "lux-mic")
        thread = t
        t.start()
        return null
    }

    private fun read(record: AudioRecord, chunk: Int, listener: MicSource.Listener) {
        val buffer = ByteArray(chunk)
        try {
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    listener.onChunk(buffer.copyOf(n))
                } else if (n < 0) {
                    if (running) listener.onProblem("reading the microphone failed (error $n)")
                    break
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            running = false
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // not recording
            }
            record.release()
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val CHUNK_MS = 40
    }
}
