package dji.sampleV5.aircraft.remote

import android.util.Log
import org.json.JSONObject

object DjiTrace {
    private const val TAG = "DJI_TRACE"

    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)

    fun p(cmdType: String, commandId: String?): String =
        "[cmd_type=$cmdType command_id=${commandId ?: "null"}]"

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
