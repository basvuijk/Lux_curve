package com.example.luxcurve.ha

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlin.math.min

/**
 * [MqttLink] on top of Eclipse Paho. Paho reconnects by itself after a connection was lost, but not
 * when the very first connection fails (broker not up yet after a reboot), so that case is retried here.
 * All callbacks to the listener are posted to the [Scheduler].
 */
class PahoMqttLink(
    private val sched: Scheduler,
    private val cfg: HaConfig,
) : MqttLink {

    private var client: MqttAsyncClient? = null
    private var listener: MqttLink.Listener? = null
    private var retry: Cancelable? = null
    private var retryMs = RETRY_MIN_MS

    @Volatile
    private var stopped = false

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            sched.post {
                if (!stopped) {
                    retryMs = RETRY_MIN_MS
                    listener?.onConnected()
                }
            }
        }

        override fun connectionLost(cause: Throwable?) {
            val reason = cause?.message ?: "connection lost"
            sched.post {
                if (!stopped) listener?.onDisconnected(reason)
            }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val payload = String(message.payload, Charsets.UTF_8)
            val retained = message.isRetained
            sched.post {
                if (!stopped) listener?.onMessage(topic, payload, retained)
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
    }

    override fun start(listener: MqttLink.Listener) {
        this.listener = listener
        val c = try {
            MqttAsyncClient(cfg.brokerUri(), "luxcurve-" + cfg.nodeId, MemoryPersistence())
        } catch (e: Exception) {
            listener.onDisconnected("Invalid broker address: " + (e.message ?: ""))
            return
        }
        c.setCallback(callback)
        client = c
        connect()
    }

    private fun connect() {
        val c = client ?: return
        if (stopped) return
        val o = MqttConnectOptions()
        o.isCleanSession = true
        o.isAutomaticReconnect = true
        o.keepAliveInterval = 30
        o.connectionTimeout = 10
        o.maxInflight = 200
        if (cfg.mqttUser.isNotBlank()) {
            o.userName = cfg.mqttUser
            o.password = cfg.mqttPassword.toCharArray()
        }
        // Home Assistant marks every entity unavailable when the panel disappears without saying goodbye.
        o.setWill(Topics(cfg.baseTopic).status, "offline".toByteArray(Charsets.UTF_8), 1, true)
        try {
            c.connect(o, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // connectComplete reports the success
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val reason = exception?.message ?: "cannot connect"
                    sched.post { onConnectFailed(reason) }
                }
            })
        } catch (e: Exception) {
            onConnectFailed(e.message ?: "cannot connect")
        }
    }

    private fun onConnectFailed(reason: String) {
        if (stopped) return
        listener?.onDisconnected(reason)
        val delay = retryMs
        retryMs = min(retryMs * 2, RETRY_MAX_MS)
        retry = sched.postDelayed(delay) {
            retry = null
            connect()
        }
    }

    override fun publish(topic: String, payload: String, retain: Boolean) {
        val c = client ?: return
        try {
            c.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retain)
        } catch (e: Exception) {
            // Not connected right now: the bridge sends its state again after the next connect.
        }
    }

    override fun subscribe(topic: String) {
        val c = client ?: return
        try {
            c.subscribe(topic, 1)
        } catch (e: Exception) {
            // see publish
        }
    }

    override fun stop() {
        stopped = true
        retry?.cancel()
        retry = null
        val c = client ?: return
        client = null
        Thread {
            try {
                if (c.isConnected) c.disconnect(1500L).waitForCompletion(3000L)
            } catch (e: Exception) {
                // closing anyway
            }
            try {
                c.close()
            } catch (e: Exception) {
                // closing anyway
            }
        }.start()
    }

    companion object {
        private const val RETRY_MIN_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
    }
}
