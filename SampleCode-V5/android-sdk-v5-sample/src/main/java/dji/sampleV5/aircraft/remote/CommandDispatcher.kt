package dji.sampleV5.aircraft.remote

import org.json.JSONObject

object CommandDispatcher {

    fun handleCommand(cmd: JSONObject, moveRunner: MoveRunner, pythonBaseUrl: String, deviceId: String) {
        val cmdType = cmd.optString("cmd_type")
        val commandIdRaw = cmd.optString("command_id")
        val commandId: String? = commandIdRaw.takeIf { it.isNotBlank() }
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        fun ack(ok: Boolean, error: String?) {
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
                DroneCommandBridge.enableVirtualStick(enabled) { ok, err ->
                    ack(ok, err)
                }
            }

            "VS_STOP" -> {
                moveRunner.stop()
                ack(true, null)
            }

            "MOVE_SEQUENCE" -> {
                val movesArr = payload.optJSONArray("moves")
                if (movesArr == null) {
                    ack(false, "Missing moves[]")
                    return
                }

                val moves = ArrayList<StickMove>()
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

                moveRunner.runSequence(moves, defaultHz = payload.optInt("defaultHz", 25))
                ack(true, null)
            }

            "SNAPSHOT" -> {
                val uploadUrl = payload.optString("upload_url")
                    .ifBlank { "${pythonBaseUrl.trimEnd('/')}/v1/drone/uploads/photo" }

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
