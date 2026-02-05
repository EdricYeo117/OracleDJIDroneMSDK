// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/MoveRunner.kt
package dji.sampleV5.aircraft.remote

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a timed loop sending virtual stick updates at a configured frequency.
 * This is important because many Virtual Stick implementations require continuous updates.
 */
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
        // Cancel any existing move
        stop()

        moving.set(true)
        val safeHz = hz.coerceIn(10, 50)
        val intervalMs = (1000L / safeHz).coerceAtLeast(20L)
        val endAt = System.currentTimeMillis() + durationMs.coerceAtLeast(50L)

        job = scope.launch {
            try {
                while (moving.get() && System.currentTimeMillis() < endAt) {
                    DroneCommandBridge.setStick(leftX, leftY, rightX, rightY)
                    delay(intervalMs)
                }
            } finally {
                moving.set(false)
                // Neutralize sticks at end
                DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
            }
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
