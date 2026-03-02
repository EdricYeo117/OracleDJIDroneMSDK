package dji.sampleV5.aircraft.remote

/**
 * Remote module file `MediaFacade.kt`: contains MediaFacade implementation details.
 */

import android.content.Context
import android.graphics.Bitmap
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileListStateListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import java.util.concurrent.atomic.AtomicBoolean
import dji.v5.manager.datacenter.camera.StreamInfo
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface MediaFacade {
    /** Real DJI shutter photo -> download original -> upload */
    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)

    /** One frame from live stream -> JPEG -> upload */
    fun snapshotFrameAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)

    /** Start raw encoded stream recording (H264/H265) using cameraStreamManager */
    fun startVideoRecording(cb: (Boolean, String?) -> Unit)

    /** Stop recording */
    fun stopVideoRecording(cb: (Boolean, String?) -> Unit)

    /** Stop raw stream recording and upload the locally recorded file */
    fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)

    // Handles `startLiveFramesUpload` behavior for the remote control module.
    fun startLiveFramesUpload(
        uploadUrl: String,
        fps: Int = 5,
        cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
        jpegQuality: Int = 75,
        cb: (Boolean, String?) -> Unit
    )

    // Handles `stopLiveFramesUpload` behavior for the remote control module.
    fun stopLiveFramesUpload(cb: (Boolean, String?) -> Unit)

    /** Continuously push stream frames (JPEG) to server for CV (human_analyser) */
    fun startLiveFramePush(
        uploadUrl: String,
        fps: Int = 5,
        cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
        jpegQuality: Int = 75,
        cb: (Boolean, String?) -> Unit
    )

    /** Stop continuous frame push */
    fun stopLiveFramePush(cb: (Boolean, String?) -> Unit)

    /** Push live stream to an RTMP ingest server (server receives the stream) */
    fun startRtmpLiveStreamAndAwait(
        rtmpUrl: String,
        cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
        timeoutMs: Long = 15_000,
        cb: (Boolean, String?) -> Unit
    )
    /** Stop any active live stream (RTMP/RTSP/etc.) */
    fun stopLiveStream(cb: (Boolean, String?) -> Unit)
}

class DefaultMediaFacade(
    private val appContext: Context,
    private val controllerApiKey: String? = null
) : MediaFacade {

    @Volatile private var liveFramesRunning = AtomicBoolean(false)
    @Volatile private var liveFrameListener: ICameraStreamManager.CameraFrameListener? = null
    @Volatile private var liveInflight = AtomicBoolean(false)
    @Volatile private var liveLastSentMs: Long = 0L
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var rawVideoRecorder: RawVideoRecorderSession? = null

    private data class RawVideoRecorderSession(
        val cameraIndex: ComponentIndexType,
        val listener: ICameraStreamManager.ReceiveStreamListener,
        val outputFile: File,
        val stream: FileOutputStream,
        val mime: AtomicReference<ICameraStreamManager.MimeType?> = AtomicReference(null),
        val bytesWritten: AtomicLong = AtomicLong(0L),
        val lock: Any = Any()
    )

    @Volatile private var liveFramePusher: LiveFramePusherSession? = null

    private data class LiveFramePusherSession(
        val cameraIndex: ComponentIndexType,
        val listener: ICameraStreamManager.CameraFrameListener,
        val uploadUrl: String,
        val minIntervalMs: Long,
        val jpegQuality: Int,
        @Volatile var lastSentAtMs: Long = 0L,
        @Volatile var inflight: Boolean = false,
        val stop: AtomicBoolean = AtomicBoolean(false)
    )

    // -------------------------
    // Public API
    // -------------------------

    override fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA] takePhotoAndUpload uploadUrl=$uploadUrl")
        io.execute {
            try {
                var photoFile: File? = captureAndDownloadOriginalPhoto()
                if (photoFile == null) {
                    photoFile = findNewestLocalJpg()
                    if (photoFile != null) {
                        DjiTrace.w("[MEDIA] DJI capture/download failed; fallback file=${photoFile.absolutePath}")
                    }
                }

                if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0L) {
                    val msg = "No photo file available (DJI capture/download failed and no fallback JPG found)."
                    DjiTrace.e("[MEDIA] $msg", null as Throwable?)
                    cb(false, msg)
                    return@execute
                }

                uploadFile(uploadUrl, photoFile, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] takePhotoAndUpload crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `snapshotFrameAndUpload` behavior for the remote control module.
    override fun snapshotFrameAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA] snapshotFrameAndUpload uploadUrl=$uploadUrl")
        io.execute {
            try {
                val frameFile = captureOneStreamFrameAsJpeg(
                    cameraIndex = ComponentIndexType.LEFT_OR_MAIN,
                    timeoutMs = 2500
                )

                if (frameFile == null || !frameFile.exists() || frameFile.length() <= 0L) {
                    val msg = "Failed to capture a stream frame (is camera stream active?)."
                    DjiTrace.e("[MEDIA] $msg", null as Throwable?)
                    cb(false, msg)
                    return@execute
                }

                uploadFile(uploadUrl, frameFile, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] snapshotFrameAndUpload crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `startVideoRecording` behavior for the remote control module.
    override fun startVideoRecording(cb: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                if (rawVideoRecorder != null) {
                    cb(false, "Raw stream recording already active")
                    return@execute
                }

                val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
                val session = startRawVideoSession(cameraIndex)
                rawVideoRecorder = session

                cb(true, null)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] startVideoRecording(raw) crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `stopVideoRecording` behavior for the remote control module.
    override fun stopVideoRecording(cb: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                val session = rawVideoRecorder
                if (session == null) {
                    cb(false, "No active raw stream recording session")
                    return@execute
                }

                val file = stopRawVideoSession(session)

                // If DJI was actually sending H265, you’ll want to name accordingly.
                // We keep file as-is for now; you can rename based on session.mime if you want.
                val bytes = file.length()
                if (bytes <= 0L) {
                    cb(false, "Raw stream file is empty (no bytes received). Is camera stream active?")
                } else {
                    cb(true, null)
                }
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] stopVideoRecording(raw) crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `stopVideoRecordingAndUpload` behavior for the remote control module.
    override fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA] stopVideoRecordingAndUpload(raw) uploadUrl=$uploadUrl")
        io.execute {
            try {
                val session = rawVideoRecorder
                if (session == null) {
                    cb(false, "No active raw stream recording session")
                    return@execute
                }

                val file = stopRawVideoSession(session)
                if (!file.exists() || file.length() <= 0L) {
                    cb(false, "Raw stream file is empty; nothing to upload")
                    return@execute
                }

                uploadFile(uploadUrl, file, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] stopVideoRecordingAndUpload(raw) crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `startRawVideoSession` behavior for the remote control module.
    private fun startRawVideoSession(cameraIndex: ComponentIndexType): RawVideoRecorderSession {
        val mgr = MediaDataCenter.getInstance().cameraStreamManager

        // Keep decode alive so first bytes arrive faster (optional but recommended for “test endpoint” usage)
        mgr.setKeepAliveDecoding(true) // :contentReference[oaicite:1]{index=1}

        val outDir = File(appContext.getExternalFilesDir(null), "drone_raw_stream").apply { mkdirs() }
        val outFile = File(outDir, "DJI_RAW_${System.currentTimeMillis()}.h264") // default; may actually be h265
        val fos = FileOutputStream(outFile, false)

        lateinit var session: RawVideoRecorderSession

        val listener = object : ICameraStreamManager.ReceiveStreamListener {
            // Handles `onReceiveStream` behavior for the remote control module.
            override fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo) {
                // First callback: capture mime for logging/diagnostics
                val mt = session.mime.get()
                if (mt == null) {
                    val newMt = info.mimeType // StreamInfo.getMimeType() :contentReference[oaicite:2]{index=2}
                    session.mime.compareAndSet(null, newMt)
                    DjiTrace.i("[MEDIA] raw stream first packet mime=$newMt ${info.width}x${info.height} fps=${info.frameRate}") // :contentReference[oaicite:3]{index=3}
                }

                if (length <= 0) return

                synchronized(session.lock) {
                    try {
                        session.stream.write(data, offset, length)
                        session.bytesWritten.addAndGet(length.toLong())
                    } catch (t: Throwable) {
                        // If writing fails, stop listener to avoid spamming
                        DjiTrace.e("[MEDIA] raw stream write failed: ${t.message}", t)
                        try { mgr.removeReceiveStreamListener(this) } catch (_: Throwable) {}
                    }
                }
            }
        }

        session = RawVideoRecorderSession(
            cameraIndex = cameraIndex,
            listener = listener,
            outputFile = outFile,
            stream = fos
        )

        mgr.addReceiveStreamListener(cameraIndex, listener) // :contentReference[oaicite:4]{index=4}
        DjiTrace.i("[MEDIA] raw stream recording started file=${outFile.absolutePath} cameraIndex=$cameraIndex")
        return session
    }
    // Handles `stopRawVideoSession` behavior for the remote control module.
    private fun stopRawVideoSession(session: RawVideoRecorderSession): File {
        val mgr = MediaDataCenter.getInstance().cameraStreamManager
        try {
            mgr.removeReceiveStreamListener(session.listener) // :contentReference[oaicite:5]{index=5}
        } catch (_: Throwable) {}

        synchronized(session.lock) {
            try {
                session.stream.flush()
            } catch (_: Throwable) {}
            try {
                session.stream.close()
            } catch (_: Throwable) {}
        }

        rawVideoRecorder = null
        DjiTrace.i(
            "[MEDIA] raw stream recording stopped file=${session.outputFile.absolutePath} " +
                    "bytes=${session.bytesWritten.get()} mime=${session.mime.get()}"
        )
        return session.outputFile
    }
    // Converter
    private fun rgba8888ToJpegFile(
        frameData: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        jpegQuality: Int
    ): File {
        val expected = width * height * 4
        val rgba = frameData.copyOfRange(offset, offset + expected)

        val argb = IntArray(width * height)
        var p = 0
        var i = 0
        while (i < argb.size && (p + 3) < rgba.size) {
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val a = rgba[p + 3].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
            i++
        }

        val bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val outDir = File(appContext.getExternalFilesDir(null), "drone_live_frames").apply { mkdirs() }
        val f = File(outDir, "FRAME_${System.currentTimeMillis()}.jpg")

        FileOutputStream(f).use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 95), os)
        }
        return f
    }

    // Handles `startLiveFramePush` behavior for the remote control module.
    override fun startLiveFramePush(
        uploadUrl: String,
        fps: Int,
        cameraIndex: ComponentIndexType,
        jpegQuality: Int,
        cb: (Boolean, String?) -> Unit
    ) {
        io.execute {
            try {
                if (liveFramePusher != null) {
                    cb(false, "Live frame push already active")
                    return@execute
                }
                if (fps <= 0) {
                    cb(false, "fps must be > 0")
                    return@execute
                }

                val mgr = MediaDataCenter.getInstance().cameraStreamManager
                // helps ensure frames arrive quickly even if no UI surface
                mgr.setKeepAliveDecoding(true)

                val minIntervalMs = (1000L / fps.toLong()).coerceAtLeast(50L)

                lateinit var session: LiveFramePusherSession

                val listener = object : ICameraStreamManager.CameraFrameListener {
                    // Handles `onFrame` behavior for the remote control module.
                    override fun onFrame(
                        frameData: ByteArray,
                        offset: Int,
                        length: Int,
                        width: Int,
                        height: Int,
                        format: ICameraStreamManager.FrameFormat
                    ) {
                        if (session.stop.get()) return
                        if (format != ICameraStreamManager.FrameFormat.RGBA_8888) return

                        val expected = width * height * 4
                        if (length < expected) return

                        val now = System.currentTimeMillis()

                        // throttle FPS
                        if (now - session.lastSentAtMs < session.minIntervalMs) return

                        // simple backpressure: skip if previous upload still inflight
                        if (session.inflight) return

                        session.lastSentAtMs = now
                        session.inflight = true

                        // Do heavy work + network on IO executor (not DJI callback thread)
                        io.execute {
                            try {
                                if (session.stop.get()) return@execute

                                val jpg = rgba8888ToJpegFile(
                                    frameData = frameData,
                                    offset = offset,
                                    width = width,
                                    height = height,
                                    jpegQuality = session.jpegQuality
                                )

                                // Add optional headers for ordering / metadata
                                val headers = mutableMapOf<String, String>()
                                if (!controllerApiKey.isNullOrBlank()) headers["X-API-Key"] = controllerApiKey
                                headers["X-Frame-Width"] = width.toString()
                                headers["X-Frame-Height"] = height.toString()
                                headers["X-Frame-Ts"] = now.toString()

                                MultipartUploader.uploadFileAsync(
                                    uploadUrl = session.uploadUrl,
                                    file = jpg,
                                    headers = headers
                                ) { ok, err ->
                                    // delete temp frame to avoid storage blowup
                                    try { jpg.delete() } catch (_: Throwable) {}
                                    session.inflight = false
                                    if (!ok) {
                                        DjiTrace.w("[LIVE_FRAMES] upload failed err=$err")
                                    }
                                }
                            } catch (t: Throwable) {
                                session.inflight = false
                                DjiTrace.e("[LIVE_FRAMES] frame processing/upload crashed: ${t.message}", t)
                            }
                        }
                    }
                }

                session = LiveFramePusherSession(
                    cameraIndex = cameraIndex,
                    listener = listener,
                    uploadUrl = uploadUrl,
                    minIntervalMs = minIntervalMs,
                    jpegQuality = jpegQuality
                )

                liveFramePusher = session
                mgr.addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.RGBA_8888, listener)

                DjiTrace.i("[LIVE_FRAMES] started uploadUrl=$uploadUrl fps=$fps cameraIndex=$cameraIndex")
                cb(true, null)
            } catch (t: Throwable) {
                DjiTrace.e("[LIVE_FRAMES] start crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    // Handles `stopLiveFramePush` behavior for the remote control module.
    override fun stopLiveFramePush(cb: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                val session = liveFramePusher ?: run {
                    cb(false, "No active live frame push session")
                    return@execute
                }
                session.stop.set(true)

                val mgr = MediaDataCenter.getInstance().cameraStreamManager
                try { mgr.removeFrameListener(session.listener) } catch (_: Throwable) {}

                liveFramePusher = null
                DjiTrace.i("[LIVE_FRAMES] stopped")
                cb(true, null)
            } catch (t: Throwable) {
                cb(false, t.toString())
            }
        }
    }
    // -------------------------
    // Upload helper
    // -------------------------

    private fun uploadFile(uploadUrl: String, file: File, cb: (Boolean, String?) -> Unit) {
        val headers = mutableMapOf<String, String>()
        if (!controllerApiKey.isNullOrBlank()) headers["X-API-Key"] = controllerApiKey

        DjiTrace.i("[MEDIA] uploading file=${file.absolutePath} bytes=${file.length()}")
        MultipartUploader.uploadFileAsync(
            uploadUrl = uploadUrl,
            file = file,
            headers = headers
        ) { ok, err ->
            DjiTrace.i("[MEDIA] upload done ok=$ok err=$err")
            cb(ok, err)
        }
    }

    // Handles `startLiveFramesUpload` behavior for the remote control module.
    override fun startLiveFramesUpload(
        uploadUrl: String,
        fps: Int,
        cameraIndex: ComponentIndexType,
        jpegQuality: Int,
        cb: (Boolean, String?) -> Unit
    ) {
        DjiTrace.i("[LIVE_FRAMES] startLiveFramesUpload url=$uploadUrl fps=$fps cameraIndex=$cameraIndex q=$jpegQuality")

        io.execute {
            try {
                if (liveFramesRunning.get()) {
                    cb(false, "Live frame upload already running")
                    return@execute
                }
                if (fps <= 0) {
                    cb(false, "fps must be > 0")
                    return@execute
                }

                val mgr = MediaDataCenter.getInstance().cameraStreamManager
                mgr.setKeepAliveDecoding(true)

                val minIntervalMs = (1000L / fps.toLong()).coerceAtLeast(80L)

                liveFramesRunning.set(true)
                liveInflight.set(false)
                liveLastSentMs = 0L

                val listener = object : ICameraStreamManager.CameraFrameListener {
                    // Handles `onFrame` behavior for the remote control module.
                    override fun onFrame(
                        frameData: ByteArray,
                        offset: Int,
                        length: Int,
                        width: Int,
                        height: Int,
                        format: ICameraStreamManager.FrameFormat
                    ) {
                        if (!liveFramesRunning.get()) return
                        if (format != ICameraStreamManager.FrameFormat.RGBA_8888) return

                        val expected = width * height * 4
                        if (length < expected) return

                        val now = System.currentTimeMillis()
                        if (now - liveLastSentMs < minIntervalMs) return

                        // backpressure: if last upload still running, skip this frame
                        if (!liveInflight.compareAndSet(false, true)) return

                        liveLastSentMs = now

                        // Do compression + upload off DJI callback thread
                        io.execute {
                            try {
                                if (!liveFramesRunning.get()) {
                                    liveInflight.set(false)
                                    return@execute
                                }

                                val jpgFile = rgbaFrameToJpegFile(
                                    frameData = frameData,
                                    offset = offset,
                                    width = width,
                                    height = height,
                                    jpegQuality = jpegQuality
                                )

                                // optional metadata headers for server-side debug
                                val headers = mutableMapOf<String, String>()
                                if (!controllerApiKey.isNullOrBlank()) headers["X-API-Key"] = controllerApiKey
                                headers["X-Frame-Width"] = width.toString()
                                headers["X-Frame-Height"] = height.toString()
                                headers["X-Frame-Ts"] = now.toString()

                                DjiTrace.i("[LIVE_FRAMES] uploading frame bytes=${jpgFile.length()} ${width}x${height}")

                                MultipartUploader.uploadFileAsync(
                                    uploadUrl = uploadUrl,
                                    file = jpgFile,
                                    headers = headers
                                ) { ok, err ->
                                    if (!ok) DjiTrace.w("[LIVE_FRAMES] upload failed err=$err")
                                    else DjiTrace.i("[LIVE_FRAMES] upload ok bytes=${jpgFile.length()}")

                                    try { jpgFile.delete() } catch (_: Throwable) {}
                                    liveInflight.set(false)
                                }
                            } catch (t: Throwable) {
                                DjiTrace.e("[LIVE_FRAMES] compress/upload crashed: ${t.message}", t)
                                liveInflight.set(false)
                            }
                        }
                    }
                }

                liveFrameListener = listener
                mgr.addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.RGBA_8888, listener)

                cb(true, null)
            } catch (t: Throwable) {
                DjiTrace.e("[LIVE_FRAMES] start failed: ${t.message}", t)
                liveFramesRunning.set(false)
                liveInflight.set(false)
                cb(false, t.toString())
            }
        }
    }

    // Handles `stopLiveFramesUpload` behavior for the remote control module.
    override fun stopLiveFramesUpload(cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[LIVE_FRAMES] stopLiveFramesUpload")
        io.execute {
            try {
                val mgr = MediaDataCenter.getInstance().cameraStreamManager
                liveFramesRunning.set(false)

                liveFrameListener?.let { l ->
                    try { mgr.removeFrameListener(l) } catch (_: Throwable) {}
                }
                liveFrameListener = null
                liveInflight.set(false)
                cb(true, null)
            } catch (t: Throwable) {
                cb(false, t.toString())
            }
        }
    }

    // -------------------------
    // PHOTO: capture + download
    // -------------------------

    private fun captureAndDownloadOriginalPhoto(): File? {
        val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
        val km = KeyManager.getInstance() ?: return null

        // 1) Ensure photo mode
        if (!setCameraMode(km, cameraIndex, CameraMode.PHOTO_NORMAL, timeoutSec = 6)) {
            DjiTrace.e("[MEDIA] setCameraMode(PHOTO_NORMAL) failed", null as Throwable?)
            return null
        }

        // 2) Trigger shoot
        if (!startShootPhoto(km, cameraIndex, timeoutSec = 8)) {
            DjiTrace.e("[MEDIA] startShootPhoto failed", null as Throwable?)
            return null
        }

        // 3) Give camera a moment to finalize media creation
        try { Thread.sleep(1200) } catch (_: Throwable) {}

        // 4) Download newest photo via MediaManager (FILTER JPG/JPEG ONLY)
        return downloadNewestPhotoViaMediaManager(cameraIndex, timeoutSec = 30)
    }

    // Handles `setCameraMode` behavior for the remote control module.
    private fun setCameraMode(
        km: dji.v5.manager.interfaces.IKeyManager,
        cameraIndex: ComponentIndexType,
        mode: CameraMode,
        timeoutSec: Long
    ): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var errMsg: String? = null

        val key = KeyTools.createCameraKey(
            CameraKey.KeyCameraMode,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )

        km.setValue(key, mode, object : CommonCallbacks.CompletionCallback {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess() {
                ok = true
                latch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                errMsg = "${error.errorCode()} ${error.description()}"
                latch.countDown()
            }
        })

        val done = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!done) DjiTrace.e("[MEDIA] setCameraMode timeout", null as Throwable?)
        if (!ok) DjiTrace.e("[MEDIA] setCameraMode failed err=$errMsg", null as Throwable?)

        // Don’t shoot immediately after switching modes
        if (done && ok) {
            try { Thread.sleep(500) } catch (_: Throwable) {}
        }

        return done && ok
    }

    // Handles `startShootPhoto` behavior for the remote control module.
    private fun startShootPhoto(
        km: dji.v5.manager.interfaces.IKeyManager,
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var errMsg: String? = null

        val actionKey = KeyTools.createCameraKey(
            CameraKey.KeyStartShootPhoto,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )

        km.performAction(actionKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                errMsg = "${error.errorCode()} ${error.description()}"
                latch.countDown()
            }
        })

        val done = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!done) DjiTrace.e("[MEDIA] startShootPhoto timeout", null as Throwable?)
        if (!ok) DjiTrace.e("[MEDIA] startShootPhoto failed err=$errMsg", null as Throwable?)
        return done && ok
    }

    // Handles `downloadNewestPhotoViaMediaManager` behavior for the remote control module.
    private fun downloadNewestPhotoViaMediaManager(
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): File? {
        val mediaManager = MediaDataCenter.getInstance().mediaManager

        // enable playback / media mode
        val enableLatch = CountDownLatch(1)
        var enableOk = false
        var enableErr: String? = null

        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess() {
                enableOk = true
                enableLatch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                enableErr = "${error.errorCode()} ${error.description()}"
                enableLatch.countDown()
            }
        })

        if (!enableLatch.await(8, TimeUnit.SECONDS) || !enableOk) {
            DjiTrace.e("[MEDIA] mediaManager.enable failed err=$enableErr", null as Throwable?)
            return null
        }

        try {
            val ds = MediaFileListDataSource.Builder().build()
            mediaManager.setMediaFileDataSource(ds)
        } catch (t: Throwable) {
            DjiTrace.w("[MEDIA] setMediaFileDataSource skipped: ${t.message}")
        }

        val upToDateLatch = CountDownLatch(1)
        val stateListener = object : MediaFileListStateListener {
            // Handles `onUpdate` behavior for the remote control module.
            override fun onUpdate(mediaFileListState: MediaFileListState) {
                if (mediaFileListState == MediaFileListState.UP_TO_DATE) {
                    upToDateLatch.countDown()
                }
            }
        }

        mediaManager.addMediaFileListStateListener(stateListener)

        try {
            val pullParam = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .build()

            mediaManager.stopPullMediaFileListFromCamera()
            mediaManager.pullMediaFileListFromCamera(pullParam, object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() {
                    // wait for UP_TO_DATE
                }

                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) {
                    DjiTrace.e(
                        "[MEDIA] pullMediaFileListFromCamera failed: ${error.errorCode()} ${error.description()}",
                        null as Throwable?
                    )
                    upToDateLatch.countDown()
                }
            })

            upToDateLatch.await(timeoutSec, TimeUnit.SECONDS)

            val list = mediaManager.mediaFileListData?.data ?: emptyList()

            // CRITICAL: filter to JPG/JPEG only (prevents MP4 being saved as .jpg)
            val photos = list.filter { mf ->
                val name = (mf.fileName ?: "").lowercase()
                name.endsWith(".jpg") || name.endsWith(".jpeg")
            }

            val newestPhoto = photos.maxByOrNull { it.fileIndex }
            if (newestPhoto == null) {
                DjiTrace.e("[MEDIA] No JPG/JPEG in media list (count=${list.size})", null as Throwable?)
                return null
            }

            val outDir = File(appContext.getExternalFilesDir(null), "drone_photos").apply { mkdirs() }
            val outFile = File(outDir, "DJI_PHOTO_${System.currentTimeMillis()}.jpg")

            val dlOk = downloadMediaFileToDisk(newestPhoto, outFile, timeoutSec = timeoutSec)
            if (!dlOk) return null

            if (!isLikelyJpeg(outFile)) {
                DjiTrace.e("[MEDIA] downloaded file failed JPEG header/footer check", null as Throwable?)
                return null
            }

            return outFile
        } finally {
            try { mediaManager.removeMediaFileListStateListener(stateListener) } catch (_: Throwable) {}
            val disableLatch = CountDownLatch(1)
            mediaManager.disable(object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() = disableLatch.countDown()
                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) = disableLatch.countDown()
            })
            disableLatch.await(4, TimeUnit.SECONDS)
        }
    }

    // -------------------------
    // VIDEO: start/stop + download latest MP4
    // -------------------------

    private fun startRecordVideo(
        km: dji.v5.manager.interfaces.IKeyManager,
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): Boolean {
        // Must be in VIDEO_NORMAL before recording
        if (!setCameraMode(km, cameraIndex, CameraMode.VIDEO_NORMAL, timeoutSec = 6)) return false

        val latch = CountDownLatch(1)
        var ok = false
        var errMsg: String? = null

        // NOTE: use KeyStartRecord (not KeyStartRecordVideo)
        val actionKey = KeyTools.createCameraKey<EmptyMsg, EmptyMsg>(
            CameraKey.KeyStartRecord,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )

        km.performAction(actionKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                errMsg = "${error.errorCode()} ${error.description()}"
                latch.countDown()
            }
        })

        val done = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!done) DjiTrace.e("[MEDIA] startRecord timeout", null as Throwable?)
        if (!ok) DjiTrace.e("[MEDIA] startRecord failed err=$errMsg", null as Throwable?)
        return done && ok
    }

    // Handles `stopRecordVideo` behavior for the remote control module.
    private fun stopRecordVideo(
        km: dji.v5.manager.interfaces.IKeyManager,
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var errMsg: String? = null

        // NOTE: use KeyStopRecord (not KeyStopRecordVideo)
        val actionKey = KeyTools.createCameraKey<EmptyMsg, EmptyMsg>(
            CameraKey.KeyStopRecord,
            cameraIndex,
            CameraLensType.CAMERA_LENS_DEFAULT
        )

        km.performAction(actionKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                errMsg = "${error.errorCode()} ${error.description()}"
                latch.countDown()
            }
        })

        val done = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!done) DjiTrace.e("[MEDIA] stopRecord timeout", null as Throwable?)
        if (!ok) DjiTrace.e("[MEDIA] stopRecord failed err=$errMsg", null as Throwable?)
        return done && ok
    }

    // Handles `downloadNewestVideoViaMediaManager` behavior for the remote control module.
    private fun downloadNewestVideoViaMediaManager(
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): File? {
        val mediaManager = MediaDataCenter.getInstance().mediaManager

        val enableLatch = CountDownLatch(1)
        var enableOk = false
        var enableErr: String? = null

        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess() {
                enableOk = true
                enableLatch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                enableErr = "${error.errorCode()} ${error.description()}"
                enableLatch.countDown()
            }
        })

        if (!enableLatch.await(8, TimeUnit.SECONDS) || !enableOk) {
            DjiTrace.e("[MEDIA] mediaManager.enable failed err=$enableErr", null as Throwable?)
            return null
        }

        try {
            val ds = MediaFileListDataSource.Builder().build()
            mediaManager.setMediaFileDataSource(ds)
        } catch (t: Throwable) {
            DjiTrace.w("[MEDIA] setMediaFileDataSource skipped: ${t.message}")
        }

        val upToDateLatch = CountDownLatch(1)
        val stateListener = object : MediaFileListStateListener {
            // Handles `onUpdate` behavior for the remote control module.
            override fun onUpdate(mediaFileListState: MediaFileListState) {
                if (mediaFileListState == MediaFileListState.UP_TO_DATE) {
                    upToDateLatch.countDown()
                }
            }
        }

        mediaManager.addMediaFileListStateListener(stateListener)

        try {
            val pullParam = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .build()

            mediaManager.stopPullMediaFileListFromCamera()
            mediaManager.pullMediaFileListFromCamera(pullParam, object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() {}
                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) {
                    DjiTrace.e(
                        "[MEDIA] pullMediaFileListFromCamera failed: ${error.errorCode()} ${error.description()}",
                        null as Throwable?
                    )
                    upToDateLatch.countDown()
                }
            })

            upToDateLatch.await(timeoutSec, TimeUnit.SECONDS)

            val list = mediaManager.mediaFileListData?.data ?: emptyList()

            // Filter to videos only
            val videos = list.filter { mf ->
                val name = (mf.fileName ?: "").lowercase()
                name.endsWith(".mp4") || name.endsWith(".mov")
            }

            val newestVideo = videos.maxByOrNull { it.fileIndex }
            if (newestVideo == null) {
                DjiTrace.e("[MEDIA] No MP4/MOV in media list (count=${list.size})", null as Throwable?)
                return null
            }

            val outDir = File(appContext.getExternalFilesDir(null), "drone_videos").apply { mkdirs() }
            val outFile = File(outDir, "DJI_VIDEO_${System.currentTimeMillis()}.mp4")

            val dlOk = downloadMediaFileToDisk(newestVideo, outFile, timeoutSec = timeoutSec)
            if (!dlOk) return null

            if (!isLikelyMp4(outFile)) {
                DjiTrace.e("[MEDIA] downloaded file failed MP4 'ftyp' check", null as Throwable?)
                return null
            }

            return outFile
        } finally {
            try { mediaManager.removeMediaFileListStateListener(stateListener) } catch (_: Throwable) {}
            val disableLatch = CountDownLatch(1)
            mediaManager.disable(object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() = disableLatch.countDown()
                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) = disableLatch.countDown()
            })
            disableLatch.await(4, TimeUnit.SECONDS)
        }
    }

    // Handles `downloadNewestVideoViaMediaManagerWithRetries` behavior for the remote control module.
    private fun downloadNewestVideoViaMediaManagerWithRetries(
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): File? {
        val attempts = 3
        for (i in 1..attempts) {
            val f = downloadNewestVideoViaMediaManager(cameraIndex, timeoutSec)
            if (f != null && f.exists() && f.length() > 0 && isLikelyMp4(f)) return f
            DjiTrace.w("[MEDIA] video download attempt $i/$attempts failed; retrying...")
            try { Thread.sleep(2500) } catch (_: Throwable) {}
        }
        return null
    }

    // -------------------------
    // Media download (chunk writer)
    // -------------------------

    private fun downloadMediaFileToDisk(mediaFile: MediaFile, outFile: File, timeoutSec: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var err: String? = null

        var totalBytes: Long = -1L         // informative only (not authoritative)
        var lastCurrent: Long = -1L
        var maxWrittenEnd: Long = 0L

        outFile.parentFile?.mkdirs()
        val raf = RandomAccessFile(outFile, "rwd")

        mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            // Handles `onStart` behavior for the remote control module.
            override fun onStart() {
                DjiTrace.i("[MEDIA] download start -> ${outFile.absolutePath}")
            }

            // Handles `onProgress` behavior for the remote control module.
            override fun onProgress(total: Long, current: Long) {
                if (total > 0) totalBytes = total
                lastCurrent = current
            }

            // Handles `onRealtimeDataUpdate` behavior for the remote control module.
            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                try {
                    raf.seek(position)
                    raf.write(data)
                    val end = position + data.size
                    if (end > maxWrittenEnd) maxWrittenEnd = end
                } catch (t: Throwable) {
                    err = "write failed: ${t.message}"
                }
            }

            // Handles `onFinish` behavior for the remote control module.
            override fun onFinish() {
                ok = true
                latch.countDown()
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                err = "${error.errorCode()} ${error.description()}"
                latch.countDown()
            }
        })

        val done = latch.await(timeoutSec, TimeUnit.SECONDS)

        try { raf.fd.sync() } catch (_: Throwable) {}
        try {
            // CRITICAL: trim/normalize to what we actually wrote
            if (maxWrittenEnd > 0) raf.setLength(maxWrittenEnd)
        } catch (_: Throwable) {}
        try { raf.close() } catch (_: Throwable) {}

        if (!done) {
            DjiTrace.e("[MEDIA] download timeout", null as Throwable?)
            return false
        }
        if (!ok) {
            DjiTrace.e("[MEDIA] download failed err=$err", null as Throwable?)
            return false
        }

        // Log the mismatch instead of failing (DJI total is often unreliable for video)
        if (totalBytes > 0 && outFile.length() != totalBytes) {
            DjiTrace.w("[MEDIA] total mismatch (informational): fileLen=${outFile.length()} expected=$totalBytes current=$lastCurrent maxWrittenEnd=$maxWrittenEnd")
        } else {
            DjiTrace.i("[MEDIA] download done fileLen=${outFile.length()} maxWrittenEnd=$maxWrittenEnd")
        }

        return true
    }

    // -------------------------
    // FRAME SNAPSHOT (stream)
    // -------------------------

    private fun captureOneStreamFrameAsJpeg(
        cameraIndex: ComponentIndexType,
        timeoutMs: Long
    ): File? {
        val mgr = MediaDataCenter.getInstance().cameraStreamManager
        val latch = CountDownLatch(1)
        var outFile: File? = null
        var err: String? = null

        val listener = object : ICameraStreamManager.CameraFrameListener {
            // Handles `onFrame` behavior for the remote control module.
            override fun onFrame(
                frameData: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                format: ICameraStreamManager.FrameFormat
            ) {
                try { mgr.removeFrameListener(this) } catch (_: Throwable) {}

                try {
                    val expected = width * height * 4
                    if (length < expected) {
                        err = "RGBA length too small: length=$length expected>=$expected"
                        return
                    }

                    val rgba = frameData.copyOfRange(offset, offset + expected)
                    val argb = IntArray(width * height)
                    var p = 0
                    var i = 0
                    while (i < argb.size && (p + 3) < rgba.size) {
                        val r = rgba[p].toInt() and 0xFF
                        val g = rgba[p + 1].toInt() and 0xFF
                        val b = rgba[p + 2].toInt() and 0xFF
                        val a = rgba[p + 3].toInt() and 0xFF
                        argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        p += 4
                        i++
                    }

                    val bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
                    val outDir = File(appContext.getExternalFilesDir(null), "drone_photos").apply { mkdirs() }
                    val f = File(outDir, "DJI_FRAME_${System.currentTimeMillis()}.jpg")

                    FileOutputStream(f).use { os ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, os)
                    }
                    outFile = f
                } catch (t: Throwable) {
                    err = t.message
                } finally {
                    latch.countDown()
                }
            }
        }

        mgr.addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.RGBA_8888, listener)

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            try { mgr.removeFrameListener(listener) } catch (_: Throwable) {}
            DjiTrace.e("[MEDIA] frame snapshot timeout", null as Throwable?)
            return null
        }

        if (outFile == null) {
            DjiTrace.e("[MEDIA] frame snapshot failed err=$err", null as Throwable?)
        }
        return outFile
    }

    // -------------------------
    // Local fallbacks / validators
    // -------------------------

    private fun findNewestLocalJpg(): File? {
        val dir = File(appContext.getExternalFilesDir(null), "drone_photos").apply { mkdirs() }
        val files = dir.listFiles() ?: return null
        return files
            .filter { it.isFile }
            .filter {
                val n = it.name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg")
            }
            .maxByOrNull { it.lastModified() }
    }

    // Handles `isLikelyJpeg` behavior for the remote control module.
    private fun isLikelyJpeg(f: File): Boolean {
        if (!f.exists() || f.length() < 4) return false
        RandomAccessFile(f, "r").use { raf ->
            val b0 = raf.readUnsignedByte()
            val b1 = raf.readUnsignedByte()
            if (b0 != 0xFF || b1 != 0xD8) return false // SOI
            raf.seek(f.length() - 2)
            val e0 = raf.readUnsignedByte()
            val e1 = raf.readUnsignedByte()
            if (e0 != 0xFF || e1 != 0xD9) return false // EOI
        }
        return true
    }

    // Handles `isLikelyMp4` behavior for the remote control module.
    private fun isLikelyMp4(f: File): Boolean {
        if (!f.exists() || f.length() < 64) return false
        RandomAccessFile(f, "r").use { raf ->
            val n = minOf(4096, f.length().toInt())
            val buf = ByteArray(n)
            raf.readFully(buf)
            val hay = String(buf, Charsets.ISO_8859_1)
            return hay.contains("ftyp")
        }
    }

    // Handles `startRtmpLiveStreamAndAwait` behavior for the remote control module.
    override fun startRtmpLiveStreamAndAwait(
        rtmpUrl: String,
        cameraIndex: ComponentIndexType,
        timeoutMs: Long,
        cb: (Boolean, String?) -> Unit
    ) {
        DjiTrace.i("[LIVE] startRtmpLiveStreamAndAwait url=$rtmpUrl cameraIndex=$cameraIndex timeoutMs=$timeoutMs")

        io.execute {
            // 0) normalize URL (ensure it includes stream name)
            val url = rtmpUrl.trim()
            val normalizedUrl =
                if (url.matches(Regex("^rtmp://.*/live/?$"))) {
                    // if someone sends only rtmp://host/live, append device stream key
                    // (or pass it in explicitly)
                    url.trimEnd('/') + "/android-controller-01"
                } else {
                    url
                }
            val done = AtomicBoolean(false)
            // Handles `finish` behavior for the remote control module.
            fun finish(ok: Boolean, err: String?) {
                if (done.compareAndSet(false, true)) cb(ok, err)
            }

            val ls = MediaDataCenter.getInstance().liveStreamManager

            val listener = object : LiveStreamStatusListener {
                // Handles `onLiveStreamStatusUpdate` behavior for the remote control module.
                override fun onLiveStreamStatusUpdate(status: LiveStreamStatus?) {
                    if (status == null) return

                    val fps = status.fps
                    val vbps = status.vbps
                    val res = status.resolution
                    val w = res?.width ?: 0
                    val h = res?.height ?: 0

                    val mediaFlowing = status.isStreaming && fps > 0 && vbps > 0 && w > 0 && h > 0
                    DjiTrace.i("[LIVE] status streaming=${status.isStreaming} fps=$fps vbps=$vbps res=${w}x$h")

                    if (mediaFlowing) {
                        ls.removeLiveStreamStatusListener(this)
                        finish(true, null)
                    }
                }

                // Handles `onError` behavior for the remote control module.
                override fun onError(error: IDJIError?) {
                    if (error == null) return
                    val msg = "${error.errorCode()} ${error.description()}"
                    DjiTrace.e("[LIVE] statusListener onError: $msg")
                    ls.removeLiveStreamStatusListener(this)
                    finish(false, msg)
                }
            }

            try {
                ls.addLiveStreamStatusListener(listener)

                val settings = LiveStreamSettings.Builder()
                    .setLiveStreamType(LiveStreamType.RTMP)
                    .setRtmpSettings(RtmpSettings.Builder().setUrl(normalizedUrl).build())
                    .build()

                // Use the same property-set approach your LiveStreamVM uses
                ls.cameraIndex = cameraIndex
                ls.liveStreamSettings = settings
                ls.liveStreamQuality = StreamQuality.HD
                ls.liveVideoBitrateMode = LiveVideoBitrateMode.AUTO
                // 1) ensure camera is in VIDEO mode (helps ensure encoder pipeline is active)
                val km = KeyManager.getInstance()
                if (km != null) {
                    setCameraMode(km, cameraIndex, CameraMode.VIDEO_NORMAL, timeoutSec = 6)
                }
                // 2) stop any old stream (best-effort)
                try {
                    ls.stopStream(object : CommonCallbacks.CompletionCallback {
                        // Handles `onSuccess` behavior for the remote control module.
                        override fun onSuccess() {}
                        // Handles `onFailure` behavior for the remote control module.
                        override fun onFailure(error: IDJIError) {}
                    })
                    Thread.sleep(500)
                } catch (_: Throwable) {}
                // Start request. DO NOT ack success here.
                ls.startStream(object : CommonCallbacks.CompletionCallback {
                    // Handles `onSuccess` behavior for the remote control module.
                    override fun onSuccess() {
                        if (done.get()) return
                        DjiTrace.i("[LIVE] startStream() success; waiting for media stats")
                    }
                    // Handles `onFailure` behavior for the remote control module.
                    override fun onFailure(error: IDJIError) {
                        if (done.get()) return
                        val msg = "${error.errorCode()} ${error.description()}"
                        ls.removeLiveStreamStatusListener(listener)
                        finish(false, msg)
                    }
                })

                // Timeout watchdog
                io.execute {
                    try {
                        Thread.sleep(timeoutMs)
                    } catch (_: InterruptedException) {}
                    if (done.compareAndSet(false, true)) {
                        ls.removeLiveStreamStatusListener(listener)
                        finish(false, "Timed out waiting for live media stats (isStreaming=${ls.isStreaming})")
                    }
                }
            } catch (t: Throwable) {
                ls.removeLiveStreamStatusListener(listener)
                finish(false, t.toString())
            }
        }
    }

    // Handles `stopLiveStream` behavior for the remote control module.
    override fun stopLiveStream(cb: (Boolean, String?) -> Unit) {
        val ls = MediaDataCenter.getInstance().liveStreamManager
        io.execute {
            ls.stopStream(object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() {
                    DjiTrace.i("[LIVE] stopStream ok")
                    cb(true, null)
                }
                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) {
                    val msg = "${error.errorCode()} ${error.description()}"
                    DjiTrace.e("[LIVE] stopStream failed: $msg")
                    cb(false, msg)
                }
            })
        }
    }

    // Handles `rgbaFrameToJpegFile` behavior for the remote control module.
    private fun rgbaFrameToJpegFile(
        frameData: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        jpegQuality: Int
    ): File {
        val expected = width * height * 4
        val rgba = frameData.copyOfRange(offset, offset + expected)

        val argb = IntArray(width * height)
        var p = 0
        var i = 0
        while (i < argb.size && (p + 3) < rgba.size) {
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val a = rgba[p + 3].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
            i++
        }

        val bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val outDir = File(appContext.getExternalFilesDir(null), "drone_live_frames").apply { mkdirs() }
        val f = File(outDir, "LIVE_${System.currentTimeMillis()}.jpg")

        FileOutputStream(f).use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 95), os)
        }
        return f
    }
}
