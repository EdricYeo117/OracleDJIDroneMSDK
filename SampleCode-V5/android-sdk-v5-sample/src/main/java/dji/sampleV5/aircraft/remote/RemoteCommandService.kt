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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


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
    private val okHttp = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // infinite for SSE
        .callTimeout(0, TimeUnit.MILLISECONDS)   // infinite (prevents “timeout” after N seconds)
        .retryOnConnectionFailure(true)
        .build()
    // NEW
    private val pending: ArrayDeque<JSONObject> = ArrayDeque()
    @Volatile private var facadesReady: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val pythonBaseUrl = try {
            PythonServerConfigStore.get(this).baseUrl()
        } catch (t: Throwable) {
            "http://192.168.1.49:8080"
        }

        val deviceId = "android-controller-01"
        val apiKey: String? = null

        startForeground(NOTIF_ID, buildNotification("Connecting to $pythonBaseUrl"))

        // NEW: keep checking readiness in the background
        startFacadeReadyWatcher()

        sseClient = SseCommandClient(
            okHttpClient = okHttp,
            baseUrl = pythonBaseUrl,
            deviceId = deviceId,
            apiKey = apiKey,
            onCommand = { json: JSONObject ->
                // GATE: queue until facades are bound
                if (!isFacadesReadyNow()) {
                    pending.addLast(json)
                    android.util.Log.w("DJI_CMD", "NOT_READY queue size=${pending.size} cmd=${json.optString("cmd_type")} id=${json.optString("command_id")}")
                    // Optional: immediately NACK so server can retry later instead of silent queue
                    // DroneHttpClient.postAck(pythonBaseUrl, deviceId, json.optString("command_id"), ok=false, error="NOT_READY: facades not bound")
                    return@SseCommandClient
                }

                // flush anything queued first
                flushPending(pythonBaseUrl, deviceId)

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

    private fun isFacadesReadyNow(): Boolean {
        // You’ll need these accessors in DroneCommandBridge (see section 2)
        val vsOk = DroneCommandBridge.virtualStickFacadeOrNull() != null
        val mediaOk = DroneCommandBridge.mediaFacadeOrNull() != null
        val ready = vsOk && mediaOk

        if (ready != facadesReady) {
            facadesReady = ready
            android.util.Log.i("DJI_CMD", "facadesReady=$facadesReady vs=$vsOk media=$mediaOk pending=${pending.size}")
        }
        return ready
    }

    private fun flushPending(pythonBaseUrl: String, deviceId: String) {
        if (!isFacadesReadyNow()) return
        if (pending.isEmpty()) return

        android.util.Log.i("DJI_CMD", "flushing pending=${pending.size}")
        while (pending.isNotEmpty()) {
            val cmd = pending.removeFirst()
            CommandDispatcher.handleCommand(
                cmd = cmd,
                moveRunner = moveRunner,
                pythonBaseUrl = pythonBaseUrl,
                deviceId = deviceId
            )
        }
    }

    private fun startFacadeReadyWatcher() {
        // Periodic poll: minimal change, very reliable.
        // If you prefer callbacks, do it in DroneCommandBridge; polling is fine for now.
        val t = Thread {
            while (isRunning) {
                try {
                    val ready = isFacadesReadyNow()
                    if (ready) {
                        // If SSE is already connected and commands queued, flushing happens in onCommand.
                        // This log just helps you see readiness transition in Logcat.
                    }
                    Thread.sleep(500)
                } catch (_: Throwable) {}
            }
        }
        t.name = "FacadeReadyWatcher"
        t.isDaemon = true
        t.start()
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
