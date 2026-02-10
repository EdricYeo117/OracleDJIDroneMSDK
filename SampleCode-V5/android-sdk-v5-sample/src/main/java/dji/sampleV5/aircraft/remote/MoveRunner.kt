// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/MoveRunner.kt
package dji.sampleV5.aircraft.remote

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a timed loop sending virtual stick updates at a configured frequency.
 * This is important because many Virtual Stick implementations require continuous updates.
 */
data class StickMove(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val durationMs: Long = 800,
    val hz: Int = 25
)

class MoveRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val moving = AtomicBoolean(false)
    private var job: Job? = null

    fun runMove(
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        durationMs: Long,
        hz: Int
    ) {
        stop()

        moving.set(true)
        val safeHz = hz.coerceIn(10, 50)
        val intervalMs = (1000L / safeHz).coerceAtLeast(20L)
        val endAt = System.currentTimeMillis() + durationMs.coerceAtLeast(50L)

        job = scope.launch {
            try {
                while (moving.get() && isActive && System.currentTimeMillis() < endAt) {
                    DroneCommandBridge.setStick(leftX, leftY, rightX, rightY)
                    delay(intervalMs)
                }
            } finally {
                moving.set(false)
                DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
            }
        }
    }

    // Existing function (whatever you currently have)
    fun runSequence(moves: List<StickMove>, defaultHz: Int) {
        runSequence(moves, defaultHz) { _, _ -> }
    }

    // NEW overload with completion callback
    fun runSequence(
        moves: List<StickMove>,
        defaultHz: Int,
        onDone: (Boolean, String?) -> Unit
    ) {
        try {
            // TODO: run your existing sequence logic asynchronously
            // When the final move completes:
            onDone(true, null)
        } catch (t: Throwable) {
            onDone(false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        moving.set(false)
        job?.cancel()
        job = null
        DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
