package dji.sampleV5.aircraft.remote

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
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface MediaFacade {
    /** Real DJI shutter photo -> download original -> upload */
    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)

    /** One frame from live stream -> JPEG -> upload */
    fun snapshotFrameAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)

    /** Start raw encoded stream recording (H264/H265) using cameraStreamManager (raw elementary stream) */
    fun startVideoRecording(cb: (Boolean, String?) -> Unit)

    /** Stop recording */
    fun stopVideoRecording(cb: (Boolean, String?) -> Unit)

    /** Stop raw stream recording and upload the local raw stream file */
    fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)
}

class DefaultMediaFacade(
    private val appContext: Context,
    private val controllerApiKey: String? = null
) : MediaFacade {

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var rawVideoRecorder: RawVideoRecorderSession? = null

    private data class RawVideoRecorderSession(
        val listener: ICameraStreamManager.ReceiveStreamListener,
        val outputFile: File,
        val stream: FileOutputStream,
        @Volatile var bytesWritten: Long = 0L
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

    override fun startVideoRecording(cb: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                if (rawVideoRecorder != null) {
                    cb(false, "Raw video recording already running")
                    return@execute
                }

                val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
                val streamManager = MediaDataCenter.getInstance().cameraStreamManager
                val outDir = File(appContext.getExternalFilesDir(null), "drone_stream_videos").apply { mkdirs() }
                val outFile = File(outDir, "DJI_STREAM_${System.currentTimeMillis()}.h26x")
                val outStream = FileOutputStream(outFile)

                val listener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
                    try {
                        val session = rawVideoRecorder ?: return@ReceiveStreamListener
                        if (session.stream != outStream) return@ReceiveStreamListener
                        if (session.bytesWritten == 0L) {
                            DjiTrace.i("[MEDIA] raw stream codec=${info.mimeType.name} writing raw elementary stream to ${session.outputFile.name}")
                        }
                        session.stream.write(data, offset, length)
                        session.bytesWritten += length.toLong()
                    } catch (t: Throwable) {
                        DjiTrace.e("[MEDIA] raw stream write failed: ${t.message}", t)
                    }
                }

                rawVideoRecorder = RawVideoRecorderSession(
                    listener = listener,
                    outputFile = outFile,
                    stream = outStream
                )
                streamManager.addReceiveStreamListener(cameraIndex, listener)
                DjiTrace.i("[MEDIA] raw stream recording started file=${outFile.absolutePath}")
                cb(true, null)
            } catch (t: Throwable) {
                cb(false, t.toString())
            }
        }
    }

    override fun stopVideoRecording(cb: (Boolean, String?) -> Unit) {
        io.execute {
            try {
                val session = rawVideoRecorder
                if (session == null) {
                    cb(false, "Raw video recording is not running")
                    return@execute
                }
                stopRawVideoSession(session)
                cb(true, null)
            } catch (t: Throwable) {
                cb(false, t.toString())
            }
        }
    }

    override fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[MEDIA] stopVideoRecordingAndUpload uploadUrl=$uploadUrl")
        io.execute {
            try {
                val session = rawVideoRecorder
                if (session == null) {
                    cb(false, "Raw video recording is not running")
                    return@execute
                }

                val videoFile = stopRawVideoSession(session)
                if (videoFile.length() <= 0L) {
                    cb(false, "Recorded video stream file is empty")
                    return@execute
                }

                uploadFile(uploadUrl, videoFile, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[MEDIA] stopVideoRecordingAndUpload crashed err=$t", t)
                cb(false, t.toString())
            }
        }
    }

    private fun stopRawVideoSession(session: RawVideoRecorderSession): File {
        MediaDataCenter.getInstance().cameraStreamManager.removeReceiveStreamListener(session.listener)
        try {
            session.stream.flush()
            session.stream.close()
        } finally {
            rawVideoRecorder = null
        }
        DjiTrace.i("[MEDIA] raw stream recording stopped file=${session.outputFile.absolutePath} bytes=${session.bytesWritten}")
        return session.outputFile
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
            override fun onSuccess() {
                ok = true
                latch.countDown()
            }

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
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

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
            override fun onSuccess() {
                enableOk = true
                enableLatch.countDown()
            }

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
                override fun onSuccess() {
                    // wait for UP_TO_DATE
                }

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
                override fun onSuccess() = disableLatch.countDown()
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
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

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
            override fun onSuccess(t: EmptyMsg?) {
                ok = true
                latch.countDown()
            }

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

    private fun downloadNewestVideoViaMediaManager(
        cameraIndex: ComponentIndexType,
        timeoutSec: Long
    ): File? {
        val mediaManager = MediaDataCenter.getInstance().mediaManager

        val enableLatch = CountDownLatch(1)
        var enableOk = false
        var enableErr: String? = null

        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                enableOk = true
                enableLatch.countDown()
            }

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
                override fun onSuccess() {}
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
                override fun onSuccess() = disableLatch.countDown()
                override fun onFailure(error: IDJIError) = disableLatch.countDown()
            })
            disableLatch.await(4, TimeUnit.SECONDS)
        }
    }

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
            override fun onStart() {
                DjiTrace.i("[MEDIA] download start -> ${outFile.absolutePath}")
            }

            override fun onProgress(total: Long, current: Long) {
                if (total > 0) totalBytes = total
                lastCurrent = current
            }

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

            override fun onFinish() {
                ok = true
                latch.countDown()
            }

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
}
