package dji.sampleV5.aircraft.remote

interface AircraftControlFacade {
    fun takeOff(cb: (Boolean, String?) -> Unit)
    fun land(cb: (Boolean, String?) -> Unit)
}