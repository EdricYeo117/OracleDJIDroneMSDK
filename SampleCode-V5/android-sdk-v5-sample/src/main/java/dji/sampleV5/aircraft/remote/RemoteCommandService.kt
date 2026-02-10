package dji.sampleV5.aircraft.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Foreground Service that keeps an SSE subscription to the Python server alive.
 *
 * Python -> Android: SSE commands
 * Android -> Python: HTTP ACK + upload (inside MediaFacade)
 *
 * Start this service after SDK is ready / product connected AND after DroneCommandBridge.bind(...)
 */
class RemoteCommandService : Service() {

    private val moveRunner = MoveRunner()
    private var sseClient: SseCommandClient? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Build base URL from your saved UI config (host/port)
        // If you don't have PythonServerConfigStore yet, replace with "http://192.168.1.49:8080"
        val pythonBaseUrl = try {
            PythonServerConfigStore.get(this).baseUrl()
        } catch (t: Throwable) {
            "http://192.168.1.49:8080"
        }

        val deviceId = "android-controller-01" // make this configurable later if you want
        val apiKey: String? = null             // or load from prefs/env if you use X-API-Key

        startForeground(NOTIF_ID, buildNotification("Connecting to $pythonBaseUrl"))

        sseClient = SseCommandClient(
            baseUrl = pythonBaseUrl,
            deviceId = deviceId,
            apiKey = apiKey,
            onCommand = { json: JSONObject ->
                // THIS is the call site you asked about:
                CommandDispatcher.handleCommand(
                    cmd = json,
                    moveRunner = moveRunner,
                    pythonBaseUrl = pythonBaseUrl,
                    deviceId = deviceId
                )
            },
            onStatus = { status ->
                updateNotification(status)
            }
        ).also { it.start() }
    }

    override fun onDestroy() {
        sseClient?.stop()
        sseClient = null

        moveRunner.stop()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(line: String): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DJI Remote Commands (SSE)",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DJI Controller (SSE)")
            .setContentText(line)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(line: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(line))
    }

    companion object {
        private const val CHANNEL_ID = "dji_remote_cmd_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
