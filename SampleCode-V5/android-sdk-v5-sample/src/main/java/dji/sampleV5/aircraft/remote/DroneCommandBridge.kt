package dji.sampleV5.aircraft.remote

/**
 * Remote module file `DroneCommandBridge.kt`: contains DroneCommandBridge implementation details.
 */

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.value.common.ComponentIndexType
import java.util.concurrent.Executors

object DroneCommandBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaIo = Executors.newSingleThreadExecutor()
    @Volatile private var virtualStick: VirtualStickFacade? = null
    @Volatile private var media: MediaFacade? = null
    @Volatile private var aircraft: AircraftControlFacade? = null

    // Handles `bindVirtualStickFacade` behavior for the remote control module.
    fun bindVirtualStickFacade(facade: VirtualStickFacade) {
        virtualStick = facade
        DjiTrace.i("[BRIDGE] bindVirtualStickFacade OK")
    }

    // Handles `unbindVirtualStickFacade` behavior for the remote control module.
    fun unbindVirtualStickFacade() {
        virtualStick = null
        DjiTrace.w("[BRIDGE] unbindVirtualStickFacade")
    }

    // Handles `bindMediaFacade` behavior for the remote control module.
    fun bindMediaFacade(facade: MediaFacade) {
        media = facade
        DjiTrace.i("[BRIDGE] bindMediaFacade OK")
    }

    // Handles `unbindMediaFacade` behavior for the remote control module.
    fun unbindMediaFacade() {
        media = null
        DjiTrace.w("[BRIDGE] unbindMediaFacade")
    }

    // Handles `virtualStickFacadeOrNull` behavior for the remote control module.
    fun virtualStickFacadeOrNull(): VirtualStickFacade? = virtualStick
    // Handles `mediaFacadeOrNull` behavior for the remote control module.
    fun mediaFacadeOrNull(): MediaFacade? = media

    // Handles `enableVirtualStick` behavior for the remote control module.
    fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        val vs = virtualStick
        if (vs == null) {
            DjiTrace.w("[BRIDGE] enableVirtualStick($enable) -> VirtualStickFacade NOT bound")
            cb(false, "VirtualStickFacade not bound")
            return
        }

        DjiTrace.i("[BRIDGE] enableVirtualStick($enable) posting to main thread")
        mainHandler.post {
            DjiTrace.i("[BRIDGE] enableVirtualStick($enable) executing on main thread")
            try {
                vs.enableVirtualStick(enable, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[BRIDGE] enableVirtualStick exception ${t.message}", t)
                cb(false, t.message ?: "enableVirtualStick failed")
            }
        }
    }

    // Handles `setStick` behavior for the remote control module.
    fun setStick(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        val vs = virtualStick
        if (vs == null) {
            DjiTrace.w("[BRIDGE] setStick dropped; VirtualStickFacade NOT bound")
            return
        }
        mainHandler.post {
            DjiTrace.i("[BRIDGE] setStick L=($leftX,$leftY) R=($rightX,$rightY)")
            try {
                vs.setLeftPosition(leftX, leftY)
                vs.setRightPosition(rightX, rightY)
            } catch (t: Throwable) {
                DjiTrace.e("[BRIDGE] setStick exception ${t.message}", t)
            }
        }
    }

    // Handles `takePhotoAndUpload` behavior for the remote control module.
    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        val m = media
        if (m == null) {
            DjiTrace.w("[BRIDGE] takePhotoAndUpload($uploadUrl) -> MediaFacade NOT bound")
            cb(false, "MediaFacade not bound")
            return
        }

//        DjiTrace.i("[BRIDGE] takePhotoAndUpload($uploadUrl) posting to main thread")
//        mainHandler.post {
        DjiTrace.i("[BRIDGE] takePhotoAndUpload($uploadUrl) dispatching to media IO thread")
        mediaIo.execute {
            DjiTrace.i("[BRIDGE] takePhotoAndUpload($uploadUrl) executing on main thread")
            try {
                m.takePhotoAndUpload(uploadUrl, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[BRIDGE] takePhotoAndUpload exception ${t.message}", t)
                cb(false, t.message ?: "takePhotoAndUpload failed")
            }
        }
    }

    // Aircraft Control
    fun bindAircraftControlFacade(facade: AircraftControlFacade) {
        aircraft = facade
        DjiTrace.i("[BRIDGE] bindAircraftControlFacade OK")
    }

    // Handles `unbindAircraftControlFacade` behavior for the remote control module.
    fun unbindAircraftControlFacade() {
        aircraft = null
        DjiTrace.w("[BRIDGE] unbindAircraftControlFacade")
    }

    // Handles `takeOff` behavior for the remote control module.
    fun takeOff(cb: (Boolean, String?) -> Unit) {
        val a = aircraft
        if (a == null) {
            cb(false, "AircraftControlFacade not bound")
            return
        }
        DjiTrace.i("[BRIDGE] takeOff() posting to main thread")
        mainHandler.post {
            try { a.takeOff(cb) } catch (t: Throwable) { cb(false, t.toString()) }
        }
    }

    // Handles `land` behavior for the remote control module.
    fun land(cb: (Boolean, String?) -> Unit) { /* same pattern */ }

    // Function to take images/stream
    fun snapshotFrameAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        val m = media
        if (m == null) {
            DjiTrace.w("[BRIDGE] snapshotFrameAndUpload($uploadUrl) -> MediaFacade NOT bound")
            cb(false, "MediaFacade not bound")
            return
        }
        mediaIo.execute {
            try {
                m.snapshotFrameAndUpload(uploadUrl, cb)
            } catch (t: Throwable) {
                DjiTrace.e("[BRIDGE] snapshotFrameAndUpload exception ${t.message}", t)
                cb(false, t.message ?: "snapshotFrameAndUpload failed")
            }
        }
    }

    // Functions for video
    fun startVideoRecording(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.startVideoRecording(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "startVideoRecording failed") }
        }
    }

    // Handles `stopVideoRecording` behavior for the remote control module.
    fun stopVideoRecording(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.stopVideoRecording(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopVideoRecording failed") }
        }
    }

    // Handles `stopVideoRecordingAndUpload` behavior for the remote control module.
    fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.stopVideoRecordingAndUpload(uploadUrl, cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopVideoRecordingAndUpload failed") }
        }
    }

    // Handles `stopLiveStream` behavior for the remote control module.
    fun stopLiveStream(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.stopLiveStream(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopLiveStream failed") }
        }
    }

    // Handles `startLiveFramePush` behavior for the remote control module.
    fun startLiveFramePush(uploadUrl: String, fps: Int = 5, cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.startLiveFramePush(uploadUrl = uploadUrl, fps = fps, cb = cb) }
            catch (t: Throwable) { cb(false, t.message ?: "startLiveFramePush failed") }
        }
    }

    // Handles `stopLiveFramePush` behavior for the remote control module.
    fun stopLiveFramePush(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.stopLiveFramePush(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopLiveFramePush failed") }
        }
    }

    // Handles `startLiveFramesUpload` behavior for the remote control module.
    fun startLiveFramesUpload(uploadUrl: String, fps: Int = 5, cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.startLiveFramesUpload(uploadUrl = uploadUrl, fps = fps, cb = cb) }
            catch (t: Throwable) { cb(false, t.message ?: "startLiveFramesUpload failed") }
        }
    }

    // Handles `stopLiveFramesUpload` behavior for the remote control module.
    fun stopLiveFramesUpload(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mediaIo.execute {
            try { m.stopLiveFramesUpload(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopLiveFramesUpload failed") }
        }
    }

    // Handles `startRtmpLiveStreamWithRetry` behavior for the remote control module.
    fun startRtmpLiveStreamWithRetry(
        rtmpUrl: String,
        cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
        cb: (Boolean, String?) -> Unit
    ) {
        val media = mediaFacadeOrNull() ?: return cb(false, "MediaFacade not bound")

        val delays = longArrayOf(0, 500, 1000, 2000, 3000)
        // Handles `attempt` behavior for the remote control module.
        fun attempt(i: Int) {
            if (i >= delays.size) return cb(false, "Failed to start livestream after retries")

            mediaIo.execute {
                if (delays[i] > 0) Thread.sleep(delays[i])

                media.startRtmpLiveStreamAndAwait(
                    rtmpUrl = rtmpUrl,
                    cameraIndex = cameraIndex,
                    timeoutMs = 15_000
                ) { ok, err ->
                    if (ok) cb(true, null)
                    else {
                        // retry only on the NOT_READY family
                        val retryable = (err?.contains("LIVE_STREAM_IS_NOT_READY") == true)
                        if (retryable) {
                            DjiTrace.w("[LIVE] retrying start (attempt=${i + 1}) err=$err")
                            media.stopLiveStream { _, _ -> attempt(i + 1) }  // best-effort reset
                        } else {
                            cb(false, err)
                        }
                    }
                }
            }
        }

        attempt(0)
    }
}
