package dji.sampleV5.aircraft.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground Service that keeps an SSE subscription to the Python server alive.
 *
 * Also starts the local controller REST server (NanoHTTPD) so intruder-server can call:
 *  - GET  http://<android_ip>:18080/v1/drone/status
 *  - POST http://<android_ip>:18080/v1/drone/vs/enable
 *  - POST http://<android_ip>:18080/v1/drone/vs/moveSequence
 *  - POST http://<android_ip>:18080/v1/drone/media/photo
 */
class RemoteCommandService : Service() {

    private val moveRunner = MoveRunner()

    private var sseClient: SseCommandClient? = null
    private var httpServer: RemoteHttpServer? = null

    private val controllerPort = 18080

    // OkHttp for SSE: MUST be infinite timeouts
    private val okHttp = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val cfg = PythonServerConfigStore.get(this)
        val pythonBaseUrl = cfg.baseUrl()
        val deviceId = "android-controller-01"

// Use the same key for SSE + controller + uploads unless you want separate keys
        val sseApiKey: String? = cfg.apiKey
        val controllerApiKey: String? = cfg.apiKey

        // Uncomment for actual drone
//        DroneCommandBridge.bindMediaFacade(
//            DefaultMediaFacade(
//                appContext = applicationContext,
//                controllerApiKey = controllerApiKey
//            )
//        )

        // BIND TEST FACADES so commands invoke methods even without a drone [For TESTING ONLY]
        DroneCommandBridge.bindMediaFacade(
            DefaultMediaFacade(
                appContext = applicationContext,
                controllerApiKey = controllerApiKey
            )
        )
        startForeground(NOTIF_ID, buildNotification("Connecting to $pythonBaseUrl"))

        // 1) Start local REST server so intruder-server can connect to 18080
        startControllerHttpServer(controllerApiKey)

        // 2) Start SSE subscription
        sseClient = SseCommandClient(
            okHttpClient = okHttp,
            baseUrl = pythonBaseUrl,
            deviceId = deviceId,
            apiKey = sseApiKey,
            onCommand = { json: JSONObject ->
                val cmdType = json.optString("cmd_type")
                val commandId = json.optString("command_id")

                // STRICT GATE: if facades not bound yet, log + NACK (no queue)
                val vsOk = DroneCommandBridge.virtualStickFacadeOrNull() != null
                val mediaOk = DroneCommandBridge.mediaFacadeOrNull() != null
                val ready = vsOk && mediaOk

                if (!ready) {
                    DjiTrace.w("[CMD] NOT_READY drop cmd_type=$cmdType command_id=$commandId vs=$vsOk media=$mediaOk")

                    // If you want server-side visibility, NACK it:
                    // DroneHttpClient.postAck(pythonBaseUrl, deviceId, commandId, ok = false, error = "NOT_READY vs=$vsOk media=$mediaOk")

                    return@SseCommandClient
                }

                DjiTrace.i("[CMD] DISPATCH cmd_type=$cmdType command_id=$commandId")
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

        DjiTrace.i("[RemoteCommandService] started: pythonBaseUrl=$pythonBaseUrl deviceId=$deviceId controllerPort=$controllerPort")
    }

    private fun startControllerHttpServer(controllerApiKey: String?) {
        if (httpServer != null) return

        try {
            httpServer = RemoteHttpServer(
                port = controllerPort,
                moveRunner = moveRunner,
                apiKey = controllerApiKey
            ).also {
                DjiTrace.i("[HTTP] starting RemoteHttpServer port=$controllerPort apiKey=${if (controllerApiKey.isNullOrBlank()) "none" else "set"}")
                it.start()
                DjiTrace.i("[HTTP] started RemoteHttpServer port=$controllerPort ip=${NetworkInfo.getLocalIpv4() ?: "unknown"}")
            }
        } catch (t: Throwable) {
            DjiTrace.e("[HTTP] failed to start RemoteHttpServer port=$controllerPort err=${t.message}", t)
        }
    }

    private fun stopControllerHttpServer() {
        try {
            httpServer?.stop()
            DjiTrace.i("[HTTP] stopped RemoteHttpServer port=$controllerPort")
        } catch (t: Throwable) {
            DjiTrace.e("[HTTP] stop failed err=${t.message}", t)
        } finally {
            httpServer = null
        }
    }

    override fun onDestroy() {
        sseClient?.stop()
        sseClient = null

        moveRunner.stop()
        stopControllerHttpServer()

        isRunning = false
        DjiTrace.w("[RemoteCommandService] destroyed")

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
