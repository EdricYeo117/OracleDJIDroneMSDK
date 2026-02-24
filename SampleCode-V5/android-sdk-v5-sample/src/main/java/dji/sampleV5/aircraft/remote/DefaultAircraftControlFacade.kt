package dji.sampleV5.aircraft.remote

import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class DefaultAircraftControlFacade(
    private val vm: BasicAircraftControlVM
) : AircraftControlFacade {

    override fun takeOff(cb: (Boolean, String?) -> Unit) {
        vm.startTakeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) = cb(true, null)
            override fun onFailure(error: IDJIError) = cb(false, error.toString())
        })
    }

    override fun land(cb: (Boolean, String?) -> Unit) {
        vm.startLanding(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) = cb(true, null)
            override fun onFailure(error: IDJIError) = cb(false, error.toString())
        })
    }
}