// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/MediaFacade.kt
package dji.sampleV5.aircraft.remote

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

interface MediaFacade {
    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)
}

/**
 * Default implementation used for testing:
 * - If DJI capture/download isn't wired yet, it uploads newest JPG from:
 *   /Android/data/<package>/files/drone_photos/
 */
class DefaultMediaFacade(
    private val appContext: Context,
    private val controllerApiKey: String? = null
) : MediaFacade {

    private val io = Executors.newSingleThreadExecutor()

    override fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA] takePhotoAndUpload uploadUrl=$uploadUrl")

        // Use IO thread for capture/find file only
        io.execute {
            try {
                var photoFile: File? = captureAndDownloadOriginal()

                if (photoFile == null) {
                    photoFile = findNewestLocalJpg()
                    if (photoFile != null) {
                        DjiTrace.w("[MEDIA] DJI not wired; fallback file=${photoFile.absolutePath}")
                    }
                }

                if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0L) {
                    val msg = "No photo file available (DJI not wired and no fallback JPG found)."
                    DjiTrace.e("[MEDIA] $msg", null)
                    cb(false, msg)
                    return@execute
                }

                val headers = mutableMapOf<String, String>()
                if (!controllerApiKey.isNullOrBlank()) headers["X-API-Key"] = controllerApiKey

                DjiTrace.i("[MEDIA] uploading file=${photoFile.absolutePath} bytes=${photoFile.length()}")

                // Async network call (safe even if called from main, but we're already on IO)
                MultipartUploader.uploadFileAsync(
                    uploadUrl = uploadUrl,
                    file = photoFile,
                    headers = headers
                ) { ok, err ->
                    DjiTrace.i("[MEDIA] upload done ok=$ok err=$err")
                    cb(ok, err)
                }

            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] takePhotoAndUpload crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    private fun captureAndDownloadOriginal(): File? {
        // TODO: wire DJI capture+download here
        DjiTrace.w("[MEDIA] captureAndDownloadOriginal() not wired; returning null")
        return null
    }

    private fun findNewestLocalJpg(): File? {
        val dir = File(appContext.getExternalFilesDir(null), "drone_photos")
        if (!dir.exists()) dir.mkdirs()

        val files = dir.listFiles() ?: return null
        var best: File? = null
        var bestTs = Long.MIN_VALUE

        for (f in files) {
            if (!f.isFile) continue
            val n = f.name.lowercase()
            if (!(n.endsWith(".jpg") || n.endsWith(".jpeg"))) continue
            val ts = f.lastModified()
            if (ts > bestTs) {
                bestTs = ts
                best = f
            }
        }
        return best
    }
}
