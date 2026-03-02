package dji.sampleV5.aircraft.remote

/**
 * Remote module file `RemoteServiceController.kt`: contains RemoteServiceController implementation details.
 */

import android.content.Context
import android.content.Intent
import android.os.Build

object RemoteServiceController {

    // Handles `start` behavior for the remote control module.
    fun start(context: Context) {
        val intent = Intent(context, RemoteCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // Handles `stop` behavior for the remote control module.
    fun stop(context: Context) {
        val intent = Intent(context, RemoteCommandService::class.java)
        context.stopService(intent)
    }
}
