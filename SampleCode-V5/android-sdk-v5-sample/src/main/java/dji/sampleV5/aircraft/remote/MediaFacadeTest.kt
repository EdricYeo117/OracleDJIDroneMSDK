// File: .../remote/MediaFacadeTest.kt
package dji.sampleV5.aircraft.remote

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

class MediaFacadeTest(
    private val context: Context,
    private val controllerApiKey: String? = null
) : MediaFacade {

    private val io = Executors.newSingleThreadExecutor()

    override fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA_TEST] takePhotoAndUpload uploadUrl=$uploadUrl")

        io.execute {
            try {
                val f = File(context.cacheDir, "dummy.jpg")
                f.writeBytes(ByteArray(256) { 0x2A })

                val headers = mutableMapOf<String, String>()
                if (!controllerApiKey.isNullOrBlank()) {
                    headers["X-API-Key"] = controllerApiKey
                }

                val (ok, err) = MultipartUploader.uploadFile(uploadUrl, f, headers)

                DjiTrace.i("[MEDIA_TEST] upload ok=$ok err=$err")
                cb(ok, err)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA_TEST] exception $t", t)
                cb(false, t.toString())
            }
        }
    }
}

