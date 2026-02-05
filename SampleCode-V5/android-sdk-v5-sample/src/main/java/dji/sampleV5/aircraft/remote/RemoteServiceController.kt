package dji.sampleV5.aircraft.remote

import android.content.Context
import android.content.Intent
import android.os.Build

object RemoteServiceController {

    fun start(context: Context) {
        val intent = Intent(context, RemoteCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, RemoteCommandService::class.java)
        context.stopService(intent)
    }
}
