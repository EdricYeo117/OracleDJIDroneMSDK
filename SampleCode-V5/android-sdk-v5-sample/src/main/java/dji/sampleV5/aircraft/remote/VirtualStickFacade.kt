// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/VirtualStickFacade.kt
package dji.sampleV5.aircraft.remote

/**
 * Remote module file `VirtualStickFacade.kt`: contains VirtualStickFacade implementation details.
 */

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam

/**
 * This facade wraps your existing VirtualStickVM.
 * Your Activity will bind an implementation of this interface to DroneCommandBridge.
 */
interface VirtualStickFacade {
    // Handles `enableVirtualStick` behavior for the remote control module.
    fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit)
    // Handles `setAdvancedModeEnabled` behavior for the remote control module.
    fun setAdvancedModeEnabled(enabled: Boolean, cb: (Boolean, String?) -> Unit)
    // Handles `setLeftPosition` behavior for the remote control module.
    fun setLeftPosition(x: Float, y: Float)
    // Handles `setRightPosition` behavior for the remote control module.
    fun setRightPosition(x: Float, y: Float)
}
