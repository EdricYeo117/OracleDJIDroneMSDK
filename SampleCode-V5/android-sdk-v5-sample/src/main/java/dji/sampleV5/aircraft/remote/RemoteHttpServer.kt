// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/RemoteHttpServer.kt
package dji.sampleV5.aircraft.remote

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

data class PhotoReq(val redUploadUrl: String)
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
    private val moveRunner: MoveRunner
) {

    private val gson = Gson()
    private val started = AtomicBoolean(false)

    private val httpd: NanoHTTPD = object : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val method = session.method
                val path = session.uri

                when {
                    method == Method.GET && path == "/health" -> {
                        jsonOk(mapOf("ok" to true, "port" to port))
                    }

                    method == Method.POST && path == "/vs/enable" -> {
                        val req = gson.fromJson(readBody(session), EnableReq::class.java)

                        DroneCommandBridge.enableVirtualStick(req.enable) { _, _ ->
                            // async callback; returning immediately
                        }
                        jsonOk(mapOf("queued" to true, "enable" to req.enable))
                    }

                    method == Method.POST && path == "/vs/move" -> {
                        val req = gson.fromJson(readBody(session), MoveReq::class.java)

                        moveRunner.runMove(
                            leftX = req.leftX,
                            leftY = req.leftY,
                            rightX = req.rightX,
                            rightY = req.rightY,
                            durationMs = req.durationMs,
                            hz = req.hz
                        )
                        jsonOk(mapOf("queued" to true))
                    }

                    method == Method.POST && path == "/vs/stop" -> {
                        moveRunner.stop()
                        jsonOk(mapOf("stopped" to true))
                    }

                    method == Method.POST && path == "/media/photo" -> {
                        val req = gson.fromJson(readBody(session), PhotoReq::class.java)

                        DroneCommandBridge.takePhotoAndUpload(req.redUploadUrl) { _, _ ->
                            // async callback; returning immediately
                        }
                        jsonOk(mapOf("queued" to true, "uploadTo" to req.redUploadUrl))
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

        private fun readBody(session: IHTTPSession): String {
            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"] ?: ""
        }

        private fun jsonOk(obj: Any): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(obj))

        private fun jsonErr(status: Response.Status, payload: Any): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(payload))
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            httpd.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            httpd.stop()
        }
    }
}
