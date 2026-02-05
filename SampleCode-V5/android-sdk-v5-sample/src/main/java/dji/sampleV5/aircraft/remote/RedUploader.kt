// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/RedUploader.kt
package dji.sampleV5.aircraft.remote
import dji.sampleV5.aircraft.remote.RedUploader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Uploads the captured photo file to RED (Node-RED) via multipart/form-data.
 * Node-RED should accept "file" field by default if you use a multipart parser.
 */
object RedUploader {
    private val client = OkHttpClient()

    /**
     * @return Pair(success, errorMessage)
     */
    fun uploadFile(uploadUrl: String, file: File): Pair<Boolean, String?> {
        val mediaType = guessMediaType(file)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = file.name,
                body = file.asRequestBody(mediaType.toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) return true to null
            return false to "Upload failed: HTTP ${resp.code} ${resp.message}"
        }
    }

    private fun guessMediaType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
