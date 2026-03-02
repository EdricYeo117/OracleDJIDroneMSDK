package dji.sampleV5.aircraft.remote

/**
 * Remote module file `MoveRunner.kt`: contains MoveRunner implementation details.
 */

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MoveRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val moving = AtomicBoolean(false)

    // Handles `stop` behavior for the remote control module.
    fun stop() {
        moving.set(false)
        job?.cancel()
        job = null
        DjiTrace.w("[MOVE] stop() called; zero sticks")
        DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
    }

    data class StickMove(
        val leftX: Float,
        val leftY: Float,
        val rightX: Float,
        val rightY: Float,
        val durationMs: Long,
        val hz: Int = 0
    )

    // Handles `runSequence` behavior for the remote control module.
    fun runSequence(moves: List<StickMove>, defaultHz: Int) {
        runSequence(moves, defaultHz) { _, _ -> }
    }

    // Handles `runSequence` behavior for the remote control module.
    fun runSequence(
        moves: List<StickMove>,
        defaultHz: Int,
        onDone: (Boolean, String?) -> Unit
    ) {
        stop()

        if (moves.isEmpty()) {
            DjiTrace.w("[MOVE] runSequence empty")
            onDone(true, null)
            return
        }

        moving.set(true)
        DjiTrace.i("[MOVE] runSequence start moves=${moves.size} defaultHz=$defaultHz")

        job = scope.launch {
            try {
                for ((idx, m) in moves.withIndex()) {
                    if (!moving.get() || !isActive) break

                    val hz = (if (m.hz > 0) m.hz else defaultHz).coerceIn(10, 50)
                    val intervalMs = (1000L / hz).coerceAtLeast(20L)
                    val dur = m.durationMs.coerceAtLeast(50L)
                    val endAt = System.currentTimeMillis() + dur

                    DjiTrace.i("[MOVE] step=$idx L=(${m.leftX},${m.leftY}) R=(${m.rightX},${m.rightY}) dur=${dur}ms hz=$hz")

                    var ticks = 0
                    while (moving.get() && isActive && System.currentTimeMillis() < endAt) {
                        DroneCommandBridge.setStick(m.leftX, m.leftY, m.rightX, m.rightY)
                        ticks++
                        delay(intervalMs)
                    }
                    DjiTrace.i("[MOVE] step=$idx done ticks=$ticks")
                }

                onDone(true, null)
            } catch (t: Throwable) {
                DjiTrace.e("[MOVE] runSequence exception ${t.message}", t)
                onDone(false, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                moving.set(false)
                DroneCommandBridge.setStick(0f, 0f, 0f, 0f)
                DjiTrace.i("[MOVE] runSequence finished; sticks zeroed")
            }
        }
    }
}
