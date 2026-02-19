package dji.sampleV5.aircraft.remote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object DroneCommandBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaIo = Executors.newSingleThreadExecutor()
    @Volatile private var virtualStick: VirtualStickFacade? = null
    @Volatile private var media: MediaFacade? = null

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
}
