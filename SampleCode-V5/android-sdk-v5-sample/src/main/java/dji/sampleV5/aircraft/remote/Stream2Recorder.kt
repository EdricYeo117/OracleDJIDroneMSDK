package dji.sampleV5.aircraft.remote

import android.content.Context
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class Stream2Recorder(
    private val appContext: Context
) {
    private val running = AtomicBoolean(false)
    private val lock = Any()

    private var currentFile: File? = null
    private var out: BufferedOutputStream? = null
    private var listener: ICameraStreamManager.ReceiveStreamListener? = null
    private var lastExt: String = "bin"

    fun start(cameraIndex: ComponentIndexType): Pair<Boolean, String?> {
        synchronized(lock) {
            if (running.get()) return Pair(false, "Stream recorder already running")

            val mgr = MediaDataCenter.getInstance().cameraStreamManager

            val outDir = File(appContext.getExternalFilesDir(null), "drone_stream").apply { mkdirs() }
            val file = File(outDir, "STREAM_${System.currentTimeMillis()}.$lastExt")
            currentFile = file
            out = BufferedOutputStream(FileOutputStream(file), 1024 * 1024)

            val l = object : ICameraStreamManager.ReceiveStreamListener {
                override fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo) {
                    // Try to infer codec to set extension (optional)
                    try {
                        val mime = info.mimeType.toString().uppercase()
                        lastExt = when {
                            mime.contains("H264") -> "h264"
                            mime.contains("H265") -> "h265"
                            else -> lastExt
                        }
                    } catch (_: Throwable) {}

                    try {
                        out?.write(data, offset, length)
                    } catch (_: Throwable) {
                        // If write fails, stop cleanly
                        stopInternal(mgr)
                    }
                }
            }

            listener = l
            running.set(true)

            // Correct signature: (cameraIndex, listener)
            mgr.addReceiveStreamListener(cameraIndex, l)

            return Pair(true, null)
        }
    }

    fun stopAndGetFile(): Pair<File?, String?> {
        synchronized(lock) {
            if (!running.get()) return Pair(null, "Stream recorder not running")
            val mgr = MediaDataCenter.getInstance().cameraStreamManager

            stopInternal(mgr)

            val f = currentFile
            if (f == null || !f.exists() || f.length() <= 0L) {
                return Pair(null, "No recorded stream data")
            }
            return Pair(f, null)
        }
    }

    private fun stopInternal(mgr: ICameraStreamManager) {
        try {
            // Correct signature: listener only
            listener?.let { mgr.removeReceiveStreamListener(it) }
        } catch (_: Throwable) {}

        try { out?.flush() } catch (_: Throwable) {}
        try { out?.close() } catch (_: Throwable) {}

        out = null
        listener = null
        running.set(false)

        // Rename to learned extension (optional)
        try {
            val f = currentFile
            if (f != null && f.exists()) {
                val wanted = f.name.substringBeforeLast('.') + "." + lastExt
                val renamed = File(f.parentFile, wanted)
                if (renamed.absolutePath != f.absolutePath) {
                    if (f.renameTo(renamed)) currentFile = renamed
                }
            }
        } catch (_: Throwable) {}
    }
}