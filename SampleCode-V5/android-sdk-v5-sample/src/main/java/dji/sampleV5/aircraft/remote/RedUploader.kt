// File: .../remote/RedUploader.kt  (your file currently defines object MultipartUploader)
package dji.sampleV5.aircraft.remote

/**
 * Remote module file `RedUploader.kt`: contains RedUploader implementation details.
 */

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

object MultipartUploader {
    private val client = OkHttpClient()

    // Existing blocking API (keep if you want)
    fun uploadFile(
        uploadUrl: String,
        file: File,
        headers: Map<String, String> = emptyMap()
    ): Pair<Boolean, String?> {

        val req = buildRequest(uploadUrl, file, headers)

        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) return true to null
            val bodyStr = runCatching { resp.body?.string() }.getOrNull()
            return false to "Upload failed: HTTP ${resp.code} ${resp.message}${if (!bodyStr.isNullOrBlank()) " body=$bodyStr" else ""}"
        }
    }

    // New async API (safe to call from main thread)
    fun uploadFileAsync(
        uploadUrl: String,
        file: File,
        headers: Map<String, String> = emptyMap(),
        onDone: (ok: Boolean, err: String?) -> Unit
    ) {
        val req = buildRequest(uploadUrl, file, headers)

        client.newCall(req).enqueue(object : Callback {
            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(call: Call, e: IOException) {
                onDone(false, e.toString())
            }

            // Handles `onResponse` behavior for the remote control module.
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        onDone(true, null)
                    } else {
                        val bodyStr = runCatching { resp.body?.string() }.getOrNull()
                        onDone(false, "Upload failed: HTTP ${resp.code} ${resp.message}${if (!bodyStr.isNullOrBlank()) " body=$bodyStr" else ""}")
                    }
                }
            }
        })
    }

    // Handles `buildRequest` behavior for the remote control module.
    private fun buildRequest(
        uploadUrl: String,
        file: File,
        headers: Map<String, String>
    ): Request {
        val mediaType = guessMediaType(file).toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()

        val reqBuilder = Request.Builder().url(uploadUrl).post(body)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        return reqBuilder.build()
    }

    // Handles `guessMediaType` behavior for the remote control module.
    private fun guessMediaType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
