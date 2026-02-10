package dji.sampleV5.aircraft.remote

import android.util.Log
import org.json.JSONObject

object CommandDispatcher {

    private const val TAG = "DJI_CMD"

    fun handleCommand(cmd: JSONObject, moveRunner: MoveRunner, pythonBaseUrl: String, deviceId: String) {
        val cmdType = cmd.optString("cmd_type")
        val commandIdRaw = cmd.optString("command_id")
        val commandId: String? = commandIdRaw.takeIf { it.isNotBlank() }
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        Log.i(TAG, "rx cmd_type=$cmdType command_id=$commandId payload=$payload")

        fun ack(ok: Boolean, error: String?) {
            Log.i(TAG, "ack cmd_type=$cmdType command_id=$commandId ok=$ok err=$error")
            DroneHttpClient.postAck(
                pythonBaseUrl = pythonBaseUrl,
                deviceId = deviceId,
                commandId = commandId,
                ok = ok,
                error = error
            )
        }

        when (cmdType) {

            "VS_ENABLE" -> {
                val enabled = payload.optBoolean("enabled", true)

                if (DroneCommandBridge.virtualStickFacadeOrNull() == null) {
                    ack(false, "VirtualStickFacade not bound")
                    return
                }

                DroneCommandBridge.enableVirtualStick(enabled) { ok, err ->
                    ack(ok, err)
                }
            }

            "VS_STOP" -> {
                moveRunner.stop()
                ack(true, null)
            }

            "MOVE_SEQUENCE" -> {
                if (DroneCommandBridge.virtualStickFacadeOrNull() == null) {
                    ack(false, "VirtualStickFacade not bound")
                    return
                }

                val movesArr = payload.optJSONArray("moves")
                if (movesArr == null) {
                    ack(false, "Missing moves[]")
                    return
                }

                val moves = ArrayList<StickMove>(movesArr.length())
                for (i in 0 until movesArr.length()) {
                    val m = movesArr.getJSONObject(i)
                    moves.add(
                        StickMove(
                            leftX = m.optDouble("leftX", 0.0).toFloat(),
                            leftY = m.optDouble("leftY", 0.0).toFloat(),
                            rightX = m.optDouble("rightX", 0.0).toFloat(),
                            rightY = m.optDouble("rightY", 0.0).toFloat(),
                            durationMs = m.optLong("durationMs", 800L),
                            hz = m.optInt("hz", 25)
                        )
                    )
                }

                val defaultHz = payload.optInt("defaultHz", 25)

                // ACK only when sequence completes (requires MoveRunner.runSequence(..., onDone) overload)
                moveRunner.runSequence(
                    moves = moves,
                    defaultHz = defaultHz,
                    onDone = { ok, err -> ack(ok, err) }
                )
            }

            // Keep SNAPSHOT, and optionally support TAKE_PHOTO as alias.
            "SNAPSHOT", "TAKE_PHOTO" -> {
                val uploadUrl = payload.optString("upload_url")
                    .ifBlank { "${pythonBaseUrl.trimEnd('/')}/v1/drone/uploads/photo" }

                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound")
                    return
                }

                DroneCommandBridge.takePhotoAndUpload(uploadUrl) { ok, err ->
                    ack(ok, err)
                }
            }

            else -> {
                ack(false, "Unknown cmd_type=$cmdType")
            }
        }
    }
}
