package dji.sampleV5.aircraft.remote

import android.os.Handler
import android.os.Looper
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class DefaultVirtualStickFacade(
    private val vm: VirtualStickVM
) : VirtualStickFacade {

    private val main = Handler(Looper.getMainLooper())

    override fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        val callback = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                DjiTrace.i("[VS] enableVirtualStick($enable) SUCCESS")
                cb(true, null)
            }

            override fun onFailure(error: IDJIError) {
                val msg = error.toString()
                DjiTrace.e("[VS] enableVirtualStick($enable) FAIL $msg", null)
                cb(false, msg)
            }
        }

        if (enable) vm.enableVirtualStick(callback) else vm.disableVirtualStick(callback)
    }

    override fun setAdvancedModeEnabled(enabled: Boolean, cb: (Boolean, String?) -> Unit) {
        // Ensure DJI calls happen on main thread
        main.post {
            try {
                if (enabled) vm.enableVirtualStickAdvancedMode()
                else vm.disableVirtualStickAdvancedMode()
                cb(true, null)
            } catch (t: Throwable) {
                cb(false, t.message ?: "setAdvancedModeEnabled failed")
            }
        }
    }

    override fun setLeftPosition(x: Float, y: Float) {
        // This ultimately sets VirtualStickManager stick ints
        main.post {
            try {
                vm.setLeftPosition(scaleStick(x), scaleStick(y))
            } catch (t: Throwable) {
                // If you want: DjiTrace.e(...)
            }
        }
    }

    override fun setRightPosition(x: Float, y: Float) {
        main.post {
            try {
                vm.setRightPosition(scaleStick(x), scaleStick(y))
            } catch (t: Throwable) {
                // If you want: DjiTrace.e(...)
            }
        }
    }

    private fun scaleStick(v: Float): Int {
        val clamped = v.coerceIn(-1f, 1f)
        // DJI Stick.MAX_STICK_POSITION_ABS is 660 in your sample, so keep 660
        return (clamped * 660f).toInt()
    }

    private fun errorString(err: IDJIError?): String {
        return try {
            err?.toString() ?: "Unknown error"
        } catch (_: Throwable) {
            "Unknown error"
        }
    }
}
