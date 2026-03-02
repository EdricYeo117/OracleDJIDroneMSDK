package dji.sampleV5.aircraft.remote

/**
 * Remote module file `DefaultAircraftControlFacade.kt`: contains DefaultAircraftControlFacade implementation details.
 */

import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class DefaultAircraftControlFacade(
    private val vm: BasicAircraftControlVM
) : AircraftControlFacade {

    // Handles `takeOff` behavior for the remote control module.
    override fun takeOff(cb: (Boolean, String?) -> Unit) {
        vm.startTakeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess(t: EmptyMsg?) = cb(true, null)
            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) = cb(false, error.toString())
        })
    }

    // Handles `land` behavior for the remote control module.
    override fun land(cb: (Boolean, String?) -> Unit) {
        vm.startLanding(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            // Handles `onSuccess` behavior for the remote control module.
            override fun onSuccess(t: EmptyMsg?) = cb(true, null)
            // Handles `onFailure` behavior for the remote control module.
            override fun onFailure(error: IDJIError) = cb(false, error.toString())
        })
    }
}
