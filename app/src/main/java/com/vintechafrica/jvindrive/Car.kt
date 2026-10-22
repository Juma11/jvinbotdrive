package com.vintechafrica.jvindrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the JVin-Bot ESP32 over its own Wi-Fi access point.
 *
 * Car API (firmware v2 and v3):
 *   GET /joy?l=-255..255&r=-255..255   left and right speed
 *   GET /stop                          stop now
 *   GET /ping                          returns OK
 * The car stops by itself if no /joy arrives for 400 ms, so this sends every 80 ms while driving.
 */
object Car {
    private const val HOST = "http://192.168.4.1"
    private const val DRIVE_PERIOD_MS = 80L
    private const val PING_PERIOD_MS = 1_000L

    @Volatile var online = false
        private set
    @Volatile var rttMs = -1
        private set
    @Volatile var driving = false
    @Volatile var left = 0
    @Volatile var right = 0

    @Volatile private var network: Network? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(c: Context) {
        if (running) return
        running = true
        bindWifi(c.applicationContext)
        worker = Thread({ loop() }, "car-link").also { it.start() }
    }

    fun stop(c: Context) {
        driving = false
        running = false
        worker?.interrupt()
        worker = null
        callback?.let {
            try {
                c.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        callback = null
        network = null
        online = false
    }

    /**
     * The car's Wi-Fi has no internet. Without this, Android (and a watch with a SIM)
     * sends requests over mobile data and the car never hears them.
     */
    private fun bindWifi(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
            }

            override fun onLost(n: Network) {
                if (network == n) network = null
            }
        }
        callback = cb
        try {
            cm.requestNetwork(req, cb)
        } catch (e: Exception) {
            // Fall back to the default network.
        }
    }

    private fun loop() {
        var wasDriving = false
        while (running) {
            val start = SystemClock.elapsedRealtime()
            if (driving) {
                wasDriving = true
                send("/joy?l=$left&r=$right")
                pause(DRIVE_PERIOD_MS - (SystemClock.elapsedRealtime() - start))
            } else {
                if (wasDriving) {
                    // Belt and braces: the car also stops on its own after 400 ms.
                    repeat(3) { send("/stop") }
                    wasDriving = false
                }
                send("/ping")
                // Wake early when the driver presses.
                var waited = 0L
                while (running && !driving && waited < PING_PERIOD_MS) {
                    pause(20)
                    waited += 20
                }
            }
        }
    }

    private fun send(path: String) {
        val t0 = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(HOST + path)
            conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.useCaches = false
            val code = conn.responseCode
            conn.inputStream.use { it.readBytes() }
            online = code == 200
            rttMs = (SystemClock.elapsedRealtime() - t0).toInt()
        } catch (e: Exception) {
            online = false
            rttMs = -1
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun pause(ms: Long) {
        if (ms <= 0) return
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    /** Arcade mix: forward and turn in -1..1, speed 0..1, to left and right in -255..255. */
    fun mix(fwd: Float, turn: Float, speed: Float): Pair<Int, Int> {
        var l = fwd + turn
        var r = fwd - turn
        val m = maxOf(1f, kotlin.math.abs(l), kotlin.math.abs(r))
        l /= m
        r /= m
        return Pair((l * 255 * speed).toInt(), (r * 255 * speed).toInt())
    }
}
