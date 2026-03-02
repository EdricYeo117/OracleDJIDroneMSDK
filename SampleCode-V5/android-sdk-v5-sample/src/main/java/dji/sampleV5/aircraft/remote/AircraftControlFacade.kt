package dji.sampleV5.aircraft.remote

/**
 * Remote module file `AircraftControlFacade.kt`: contains AircraftControlFacade implementation details.
 */

interface AircraftControlFacade {
    // Handles `takeOff` behavior for the remote control module.
    fun takeOff(cb: (Boolean, String?) -> Unit)
    // Handles `land` behavior for the remote control module.
    fun land(cb: (Boolean, String?) -> Unit)
}
