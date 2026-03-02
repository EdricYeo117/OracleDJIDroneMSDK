package dji.sampleV5.aircraft.remote

/**
 * Remote module file `DjiTrace.kt`: contains DjiTrace implementation details.
 */

import android.util.Log
import org.json.JSONObject

object DjiTrace {
    private const val TAG = "DJI_TRACE"

    // Handles `i` behavior for the remote control module.
    fun i(msg: String) = Log.i(TAG, msg)
    // Handles `w` behavior for the remote control module.
    fun w(msg: String) = Log.w(TAG, msg)
    // Handles `e` behavior for the remote control module.
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

    // Handles `p` behavior for the remote control module.
    fun p(cmdType: String, commandId: String?): String =
        "[cmd_type=$cmdType command_id=${commandId ?: "null"}]"

    // Handles `json` behavior for the remote control module.
    fun json(obj: Any?): String = try {
        when (obj) {
            null -> "null"
            is JSONObject -> obj.toString()
            else -> obj.toString()
        }
    } catch (_: Throwable) {
        "<unprintable>"
    }
}
