package dji.sampleV5.aircraft.remote

/**
 * Remote module file `DefaultVirtualStickFacade.kt`: contains DefaultVirtualStickFacade implementation details.
 */

import android.os.Handler
import android.os.Looper
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class DefaultVirtualStickFacade(
    private val vm: VirtualStickVM
) : VirtualStickFacade {

    private val main = Handler(Looper.getMainLooper())

    // Handles `enableVirtualStick` behavior for the remote control module.
    override fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        val callback = object : CommonCallbacks.CompletionCallback {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess() {
                DjiTrace.i("[VS] enableVirtualStick($enable) SUCCESS")
                cb(true, null)
            }

            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) {
                val msg = error.toString()
                DjiTrace.e("[VS] enableVirtualStick($enable) FAIL $msg", null)
                cb(false, msg)
            }
        }

        if (enable) vm.enableVirtualStick(callback) else vm.disableVirtualStick(callback)
    }

    // Handles `setAdvancedModeEnabled` behavior for the remote control module.
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

    // Handles `setLeftPosition` behavior for the remote control module.
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

    // Handles `setRightPosition` behavior for the remote control module.
    override fun setRightPosition(x: Float, y: Float) {
        main.post {
            try {
                vm.setRightPosition(scaleStick(x), scaleStick(y))
            } catch (t: Throwable) {
                // If you want: DjiTrace.e(...)
            }
        }
    }

    // Handles `scaleStick` behavior for the remote control module.
    private fun scaleStick(v: Float): Int {
        val clamped = v.coerceIn(-1f, 1f)
        // DJI Stick.MAX_STICK_POSITION_ABS is 660 in your sample, so keep 660
        return (clamped * 660f).toInt()
    }

    // Handles `errorString` behavior for the remote control module.
    private fun errorString(err: IDJIError?): String {
        return try {
            err?.toString() ?: "Unknown error"
        } catch (_: Throwable) {
            "Unknown error"
        }
    }
}
