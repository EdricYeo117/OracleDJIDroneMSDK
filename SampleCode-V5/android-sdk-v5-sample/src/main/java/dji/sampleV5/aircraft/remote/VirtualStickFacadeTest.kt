// File: .../remote/VirtualStickFacadeTest.kt
package dji.sampleV5.aircraft.remote

class VirtualStickFacadeTest : VirtualStickFacade {
    override fun enableVirtualStick(enable: Boolean, cb: (Boolean, String?) -> Unit) {
        DjiTrace.i("[VS_TEST] enableVirtualStick(enable=$enable)")
        cb(true, null)
    }

    override fun setLeftPosition(x: Float, y: Float) {
        DjiTrace.i("[VS_TEST] setLeftPosition x=$x y=$y")
    }

    override fun setRightPosition(x: Float, y: Float) {
        DjiTrace.i("[VS_TEST] setRightPosition x=$x y=$y")
    }
}
