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

    fun runSequence(moves: List<StickMove>, defaultHz: Int = 25) {
        stop()
        moving.set(true)

        job = scope.launch {
            try {
                for (m in moves) {
                    if (!moving.get() || !isActive) break

                    val safeHz = (if (m.hz > 0) m.hz else defaultHz).coerceIn(10, 50)
                    val intervalMs = (1000L / safeHz).coerceAtLeast(20L)
                    val endAt = System.currentTimeMillis() + m.durationMs.coerceAtLeast(50L)

                    while (moving.get() && isActive && System.currentTimeMillis() < endAt) {
                        DroneCommandBridge.setStick(m.leftX, m.leftY, m.rightX, m.rightY)
                        delay(intervalMs)
                    }

                    // small neutral pause between segments
                    DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
                    delay(80L)
                }
            } finally {
                moving.set(false)
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
