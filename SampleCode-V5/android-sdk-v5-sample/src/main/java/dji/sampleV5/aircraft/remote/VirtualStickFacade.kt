// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/VirtualStickFacade.kt
package dji.sampleV5.aircraft.remote

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam

/**
 * This facade wraps your existing VirtualStickVM.
 * Your Activity will bind an implementation of this interface to DroneCommandBridge.
 */
interface VirtualStickFacade {
    fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit)
    fun setAdvancedModeEnabled(enabled: Boolean, cb: (Boolean, String?) -> Unit)
    fun setLeftPosition(x: Float, y: Float)
    fun setRightPosition(x: Float, y: Float)
}
