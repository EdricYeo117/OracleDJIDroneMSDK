// File: .../remote/VirtualStickFacadeTest.kt
package dji.sampleV5.aircraft.remote

/**
 * Remote module file `VirtualStickFacadeTest.kt`: contains VirtualStickFacadeTest implementation details.
 */

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam

class VirtualStickFacadeTest : VirtualStickFacade {
    // Handles `enableVirtualStick` behavior for the remote control module.
    override fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[VS_TEST] enableVirtualStick(enable=$enable)")
        cb(true, null)
    }

    // Handles `setAdvancedModeEnabled` behavior for the remote control module.
    override fun setAdvancedModeEnabled(enabled: Boolean, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[VS_TEST] setAdvancedModeEnabled(enabled=$enabled)")
        cb(true, null)
    }

    // Handles `setLeftPosition` behavior for the remote control module.
    override fun setLeftPosition(x: Float, y: Float) {
        DjiTrace.i("[VS_TEST] setLeftPosition x=$x y=$y")
    }

    // Handles `setRightPosition` behavior for the remote control module.
    override fun setRightPosition(x: Float, y: Float) {
        DjiTrace.i("[VS_TEST] setRightPosition x=$x y=$y")
    }
}

