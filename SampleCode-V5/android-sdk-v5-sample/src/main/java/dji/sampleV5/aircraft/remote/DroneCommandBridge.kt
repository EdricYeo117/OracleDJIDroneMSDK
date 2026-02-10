// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/DroneCommandBridge.kt
package dji.sampleV5.aircraft.remote

import android.os.Handler
import android.os.Looper

/**
 * Bridge between "remote server/service" code and your existing ViewModel layer.
 *
 * IMPORTANT:
 * - Do not create ViewModels inside Service.
 * - Bind facades from an Activity/Fragment that already owns the VMs.
 */
object DroneCommandBridge {

    @Volatile private var virtualStick: VirtualStickFacade? = null
    @Volatile private var media: MediaFacade? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun bind(virtualStickFacade: VirtualStickFacade, mediaFacade: MediaFacade) {
        this.virtualStick = virtualStickFacade
        this.media = mediaFacade
    }

    fun unbind() {
        this.virtualStick = null
        this.media = null
    }


    fun bindVirtualStick(f: VirtualStickFacade) {
        virtualStick = f
        android.util.Log.i("DJI_CMD", "VirtualStickFacade bound")
    }

    fun bindMedia(f: MediaFacade) {
        media = f
        android.util.Log.i("DJI_CMD", "MediaFacade bound")
    }

    fun unbindAll() {
        virtualStick = null
        media = null
        android.util.Log.w("DJI_CMD", "facades unbound")
    }

    fun virtualStickFacadeOrNull(): VirtualStickFacade? = virtualStick
    fun mediaFacadeOrNull(): MediaFacade? = media

    fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        val vs = virtualStick ?: return cb(false, "VirtualStickFacade not bound")
        mainHandler.post {
            try {
                vs.enableVirtualStick(enable, cb)
            } catch (t: Throwable) {
                cb(false, t.message ?: "enableVirtualStick failed")
            }
        }
    }

    /**
     * Set stick positions once (MoveRunner calls this repeatedly at ~Hz).
     */
    fun setStick(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        val vs = virtualStick ?: return
        mainHandler.post {
            vs.setLeftPosition(leftX, leftY)
            vs.setRightPosition(rightX, rightY)
        }
    }

    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit) {
        val m = media ?: return cb(false, "MediaFacade not bound")
        mainHandler.post {
            try {
                m.takePhotoAndUpload(uploadUrl, cb)
            } catch (t: Throwable) {
                cb(false, t.message ?: "takePhotoAndUpload failed")
            }
        }
    }
}
