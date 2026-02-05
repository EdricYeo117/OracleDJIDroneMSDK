// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/RemoteCommandService.kt
package dji.sampleV5.aircraft.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that keeps the embedded HTTP server alive.
 * Start this service from your Aircraft host Activity after SDK is ready / product connected.
 */
class RemoteCommandService : Service() {

    private var server: RemoteHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())

        // Start HTTP server
        server = RemoteHttpServer(
            port = DEFAULT_PORT,
            moveRunner = MoveRunner()
        ).also {
            it.start()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DJI Remote Commands",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DJI Remote Command Listener")
            .setContentText("Listening on port $DEFAULT_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val CHANNEL_ID = "dji_remote_cmd_channel"
        private const val NOTIF_ID = 1001
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
