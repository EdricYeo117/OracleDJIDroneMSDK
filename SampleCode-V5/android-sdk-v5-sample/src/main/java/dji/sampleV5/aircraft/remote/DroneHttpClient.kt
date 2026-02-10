package dji.sampleV5.aircraft.remote

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object DroneHttpClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Android -> Python API call
     * POST {pythonBaseUrl}/v1/drone/ack
     */
    fun postAck(
        pythonBaseUrl: String,
        deviceId: String,
        commandId: String?,
        ok: Boolean,
        error: String?
    ) {
        if (commandId.isNullOrBlank()) return

        val bodyJson = JSONObject()
            .put("device_id", deviceId)
            .put("command_id", commandId)
            .put("ok", ok)
            .put("error", error)

        val req = Request.Builder()
            .url("${pythonBaseUrl.trimEnd('/')}/v1/drone/ack")
            .post(bodyJson.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // optional: Log.e("DroneHttpClient", "ack failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
