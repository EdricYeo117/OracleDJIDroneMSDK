package dji.sampleV5.aircraft.remote

/**
 * Remote module file `DroneHttpClient.kt`: contains DroneHttpClient implementation details.
 */

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object DroneHttpClient {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // Handles `postAck` behavior for the remote control module.
    fun postAck(
        pythonBaseUrl: String,
        deviceId: String,
        commandId: String?,
        ok: Boolean,
        error: String?
    ) {
        val url = "${pythonBaseUrl.trimEnd('/')}/v1/drone/ack"
        val bodyJson = buildString {
            append("{")
            append("\"device_id\":\"").append(deviceId).append("\",")
            append("\"command_id\":").append(if (commandId == null) "null" else "\"$commandId\"").append(",")
            append("\"ok\":").append(ok)
            if (error != null) {
                append(",\"error\":\"").append(error.replace("\"", "\\\"")).append("\"")
            }
            append("}")
        }

        DjiTrace.i("[ACK] POST url=$url body=$bodyJson")

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        client.newCall(req).enqueue(object : Callback {
            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(call: Call, e: IOException) {
                DjiTrace.e("[ACK] FAILED url=${call.request().url} err=${e.message}", e)
            }

            // Handles `onResponse` behavior for the remote control module.
            override fun onResponse(call: Call, response: Response) {
                DjiTrace.i("[ACK] OK code=${response.code} url=${call.request().url}")
                response.close()
            }
        })
    }
}
