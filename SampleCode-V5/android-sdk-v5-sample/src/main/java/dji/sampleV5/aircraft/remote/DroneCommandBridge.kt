package dji.sampleV5.aircraft.remote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object DroneCommandBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaIo = Executors.newSingleThreadExecutor()
    @Volatile private var virtualStick: VirtualStickFacade? = null
    @Volatile private var media: MediaFacade? = null
    @Volatile private var aircraft: AircraftControlFacade? = null

    fun bindVirtualStickFacade(facade: VirtualStickFacade) {
        virtualStick = facade
        DjiTrace.i("[BRIDGE] bindVirtualStickFacade OK")
    }

    fun unbindVirtualStickFacade() {
        virtualStick = null
        DjiTrace.w("[BRIDGE] unbindVirtualStickFacade")
    }

    fun bindMediaFacade(facade: MediaFacade) {
        media = facade
        DjiTrace.i("[BRIDGE] bindMediaFacade OK")
    }

    fun unbindMediaFacade() {
        media = null
        DjiTrace.w("[BRIDGE] unbindMediaFacade")
    }

    fun virtualStickFacadeOrNull(): VirtualStickFacade? = virtualStick
    fun mediaFacadeOrNull(): MediaFacade? = media

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

    fun unbindAircraftControlFacade() {
        aircraft = null
        DjiTrace.w("[BRIDGE] unbindAircraftControlFacade")
    }

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
        mainHandler.post {
            try { m.startVideoRecording(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "startVideoRecording failed") }
        }
    }

    fun stopVideoRecording(cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mainHandler.post {
            try { m.stopVideoRecording(cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopVideoRecording failed") }
        }
    }

    fun stopVideoRecordingAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mainHandler.post {
            try { m.stopVideoRecordingAndUpload(uploadUrl, cb) }
            catch (t: Throwable) { cb(false, t.message ?: "stopVideoRecordingAndUpload failed") }
        }
    }
}
