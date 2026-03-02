// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/RemoteHttpServer.kt
package dji.sampleV5.aircraft.remote

/**
 * Remote module file `RemoteHttpServer.kt`: contains RemoteHttpServer implementation details.
 */

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicBoolean

// ---- Request DTOs (TOP LEVEL) ----
data class EnableReq(val enable: Boolean)

data class MoveReq(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val durationMs: Long = 800,
    val hz: Int = 25
)

data class MoveSequenceReq(
    val moves: List<MoveRunner.StickMove>,
    val defaultHz: Int = 25
)

data class PhotoReq(val uploadUrl: String)

/**
 * Embedded HTTP server that receives JSON commands from RED (Ubuntu / Node-RED)
 * and dispatches to DroneCommandBridge + MoveRunner.
 *
 * Endpoints:
 *  POST /vs/enable   { "enable": true }
 *  POST /vs/move     { leftX,leftY,rightX,rightY,durationMs,hz }
 *  POST /vs/stop     (no body)
 *  POST /media/photo { "redUploadUrl": "http://<RED_IP>:1880/upload" }
 *  GET  /health      (simple health check)
 */
class RemoteHttpServer(
    private val port: Int,
    private val moveRunner: MoveRunner,
    private val apiKey: String? = null, // <- NEW (CONTROLLER_API_KEY)
) {
    private val gson = Gson()
    private val started = AtomicBoolean(false)

    private val httpd: NanoHTTPD = object : NanoHTTPD(port) {

        // Handles `enforceApiKey` behavior for the remote control module.
        private fun enforceApiKey(session: IHTTPSession): Response? {
            val expected = apiKey?.trim().orEmpty()
            if (expected.isEmpty()) return null // auth disabled
            val got = session.headers["x-api-key"]?.trim().orEmpty()
            return if (got == expected) null
            else jsonErr(
                status = Response.Status.UNAUTHORIZED,
                payload = mapOf("error" to "unauthorized")
            )
        }
        // Handles `serve` behavior for the remote control module.
        override fun serve(session: IHTTPSession): Response {
            enforceApiKey(session)?.let { return it }
            return try {
                val method = session.method
                val path = session.uri

                when {
                    method == Method.GET && path == "/v1/drone/status" -> {
                        jsonOk(
                            mapOf(
                                "ok" to true,
                                "port" to port,
                                "ip" to (NetworkInfo.getLocalIpv4() ?: "unknown")
                            )
                        )
                    }

                    method == Method.POST && path == "/v1/drone/vs/enable" -> {
                        val req = gson.fromJson(readBody(session), EnableReq::class.java)
                        DroneCommandBridge.enableVirtualStick(req.enable) { _, _ -> }
                        jsonOk(mapOf("queued" to true, "enable" to req.enable))
                    }

                    method == Method.POST && path == "/v1/drone/vs/moveSequence" -> {
                        val req = gson.fromJson(readBody(session), MoveSequenceReq::class.java)
                        moveRunner.runSequence(req.moves, req.defaultHz)
                        jsonOk(mapOf("queued" to true, "count" to req.moves.size))
                    }

                    method == Method.POST && path == "/v1/drone/vs/stop" -> {
                        moveRunner.stop()
                        jsonOk(mapOf("stopped" to true))
                    }

                    method == Method.POST && path == "/v1/drone/media/photo" -> {
                        val req = gson.fromJson(readBody(session), PhotoReq::class.java)
                        DroneCommandBridge.takePhotoAndUpload(req.uploadUrl) { _, _ -> }
                        jsonOk(mapOf("queued" to true, "uploadTo" to req.uploadUrl))
                    }

                    else -> jsonErr(
                        status = Response.Status.NOT_FOUND,
                        payload = mapOf("error" to "not_found", "path" to path, "method" to method.name)
                    )
                }

            } catch (e: Exception) {
                jsonErr(
                    status = Response.Status.INTERNAL_ERROR,
                    payload = mapOf("error" to "exception", "message" to (e.message ?: "unknown"))
                )
            }
        }

        // Handles `readBody` behavior for the remote control module.
        private fun readBody(session: IHTTPSession): String {
            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"] ?: ""
        }

        // Handles `jsonOk` behavior for the remote control module.
        private fun jsonOk(obj: Any): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(obj))

        // Handles `jsonErr` behavior for the remote control module.
        private fun jsonErr(status: Response.Status, payload: Any): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(payload))
    }

    // Handles `start` behavior for the remote control module.
    fun start() {
        if (started.compareAndSet(false, true)) {
            httpd.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    // Handles `stop` behavior for the remote control module.
    fun stop() {
        if (started.compareAndSet(true, false)) {
            httpd.stop()
        }
    }
}
