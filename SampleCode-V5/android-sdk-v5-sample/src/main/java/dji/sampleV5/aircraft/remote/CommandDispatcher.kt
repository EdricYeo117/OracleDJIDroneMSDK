package dji.sampleV5.aircraft.remote

import org.json.JSONArray
import org.json.JSONObject

object CommandDispatcher {

    fun handleCommand(cmd: JSONObject, moveRunner: MoveRunner, pythonBaseUrl: String, deviceId: String) {
        val cmdType = cmd.optString("cmd_type")
        val commandIdRaw = cmd.optString("command_id")
        val commandId: String? = commandIdRaw.takeIf { it.isNotBlank() }
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [DISPATCH_RX] payload=${DjiTrace.json(payload)} pythonBaseUrl=$pythonBaseUrl deviceId=$deviceId")

        fun ack(ok: Boolean, error: String?) {
            DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [ACK->POST] ok=$ok error=$error")
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
                val advanced = payload.optBoolean("advanced", false)

                DroneCommandBridge.enableVirtualStick(enabled) { ok, err ->
                    if (!ok) {
                        ack(false, err)
                        return@enableVirtualStick
                    }

                    // If disabling, force advanced off (clean)
                    val advTarget = if (enabled) advanced else false

                    val vs = DroneCommandBridge.virtualStickFacadeOrNull()
                    if (vs == null) {
                        ack(false, "VirtualStickFacade not bound after enable")
                        return@enableVirtualStick
                    }

                    DjiTrace.i("[VS_ENABLE] setAdvancedModeEnabled($advTarget)")
                    vs.setAdvancedModeEnabled(advTarget) { ok2, err2 ->
                        if (!ok2) {
                            ack(false, err2 ?: "setAdvancedModeEnabled failed")
                        } else {
                            ack(true, null)
                        }
                    }
                }
            }

            "MOVE_SEQUENCE" -> {
                val movesArr = payload.optJSONArray("moves")
                val defaultHz = payload.optInt("defaultHz", 25)
                if (movesArr == null) {
                    DjiTrace.w("${DjiTrace.p(cmdType, commandId)} [MOVE_SEQUENCE] Missing moves[]")
                    ack(false, "Missing moves[]")
                    return
                }

                val moves = parseMoves(movesArr)
                DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [MOVE_SEQUENCE] movesCount=${moves.size} defaultHz=$defaultHz")

                if (DroneCommandBridge.virtualStickFacadeOrNull() == null) {
                    DjiTrace.w("${DjiTrace.p(cmdType, commandId)} [MOVE_SEQUENCE] VirtualStickFacade NOT bound")
                    ack(false, "VirtualStickFacade not bound")
                    return
                }

                moveRunner.runSequence(moves, defaultHz) { ok, err ->
                    DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [MOVE_SEQUENCE] onDone ok=$ok err=$err")
                    ack(ok, err)
                }
            }

            "SNAPSHOT", "TAKE_PHOTO" -> {
                val uploadUrl =
                    payload.optString("uploadUrl").ifBlank { payload.optString("upload_url") }
                        .ifBlank { "${pythonBaseUrl.trimEnd('/')}/v1/drone/uploads/photo" }

                DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [SNAPSHOT] uploadUrl=$uploadUrl")

                DroneCommandBridge.takePhotoAndUpload(uploadUrl) { ok, err ->
                    DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [SNAPSHOT] cb ok=$ok err=$err")
                    ack(ok, err)
                }
            }

            "VS_STOP" -> {
                DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [VS_STOP] stop requested")
                moveRunner.stop()
                ack(true, null)
            }
            "TAKEOFF" -> {
                DroneCommandBridge.takeOff { ok, err ->
                    DjiTrace.i("[TAKEOFF] cb ok=$ok err=$err")
                    ack(ok, err)
                }
            }
            "FRAME_SNAPSHOT" -> {
                val uploadUrl = payload.optString("uploadUrl").ifBlank { payload.optString("upload_url") }
                    .ifBlank { "${pythonBaseUrl.trimEnd('/')}/v1/drone/uploads/photo" }

                DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [FRAME_SNAPSHOT] uploadUrl=$uploadUrl")
                DroneCommandBridge.snapshotFrameAndUpload(uploadUrl) { ok, err ->
                    DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [FRAME_SNAPSHOT] cb ok=$ok err=$err")
                    ack(ok, err)
                }
            }

            "VIDEO_START" -> {
                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound"); return
                }
                DroneCommandBridge.startVideoRecording { ok, err -> ack(ok, err) }
            }

            "VIDEO_STOP" -> {
                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound"); return
                }
                DroneCommandBridge.stopVideoRecording { ok, err -> ack(ok, err) }
            }

            "VIDEO_STOP_AND_UPLOAD" -> {
                val uploadUrl = payload.optString("upload_url")
                    .ifBlank { payload.optString("uploadUrl") }
                    // You must implement /v1/drone/uploads/video server-side if you use this:
                    .ifBlank { "${pythonBaseUrl.trimEnd('/')}/v1/drone/uploads/video" }

                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound"); return
                }
                DroneCommandBridge.stopVideoRecordingAndUpload(uploadUrl) { ok, err -> ack(ok, err) }
            }
            "LIVESTREAM_START" -> {
                val rtmpUrl = payload.optString("rtmp_url")
                    .ifBlank { payload.optString("rtmpUrl") }

                if (rtmpUrl.isBlank()) {
                    ack(false, "Missing payload.rtmp_url")
                    return
                }
                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound")
                    return
                }

                DroneCommandBridge.startRtmpLiveStream(rtmpUrl) { ok, err -> ack(ok, err) }
            }

            "LIVESTREAM_STOP" -> {
                if (DroneCommandBridge.mediaFacadeOrNull() == null) {
                    ack(false, "MediaFacade not bound")
                    return
                }
                DroneCommandBridge.stopLiveStream { ok, err -> ack(ok, err) }
            }
            else -> {
                DjiTrace.w("${DjiTrace.p(cmdType, commandId)} [DISPATCH] Unknown cmd_type=$cmdType")
                ack(false, "Unknown cmd_type=$cmdType")
            }
        }
    }

    private fun parseMoves(arr: JSONArray): List<MoveRunner.StickMove> {
        val out = ArrayList<MoveRunner.StickMove>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val leftX = o.optDouble("leftX", 0.0).toFloat()
            val leftY = o.optDouble("leftY", 0.0).toFloat()
            val rightX = o.optDouble("rightX", 0.0).toFloat()
            val rightY = o.optDouble("rightY", 0.0).toFloat()
            val dur = o.optLong("durationMs", 500L)
            val hz = o.optInt("hz", 0)
            out.add(MoveRunner.StickMove(leftX, leftY, rightX, rightY, dur, hz))
        }
        return out
    }
}
