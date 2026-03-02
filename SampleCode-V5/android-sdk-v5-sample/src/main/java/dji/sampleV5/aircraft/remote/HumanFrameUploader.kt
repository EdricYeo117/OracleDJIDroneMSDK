package dji.sampleV5.aircraft.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class HumanFrameUploader(
    private val apiKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun uploadJpegToUrl(uploadUrl: String, jpegFile: File, headers: Map<String, String> = emptyMap()) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "frame.jpg",
                jpegFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val reqBuilder = Request.Builder().url(uploadUrl).post(body)

        if (!apiKey.isNullOrBlank()) reqBuilder.addHeader("X-API-Key", apiKey)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        client.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                DjiTrace.w("[HUMAN_FRAME_UPLOAD] failed: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        DjiTrace.w("[HUMAN_FRAME_UPLOAD] HTTP ${it.code}: ${it.body?.string()}")
                    }
                }
            }
        })
    }
}