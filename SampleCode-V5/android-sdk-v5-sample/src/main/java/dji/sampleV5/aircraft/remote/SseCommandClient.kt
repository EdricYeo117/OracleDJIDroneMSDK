package dji.sampleV5.aircraft.remote

/**
 * Remote module file `SseCommandClient.kt`: contains SseCommandClient implementation details.
 */

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

class SseCommandClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String,
    private val apiKey: String?,
    private val onStatus: (String) -> Unit,
    private val onCommand: (JSONObject) -> Unit
) {
    @Volatile private var eventSource: okhttp3.sse.EventSource? = null
    @Volatile private var stopping = false

    // Handles `start` behavior for the remote control module.
    fun start() {
        stopping = false

        val url = "$baseUrl/v1/drone/stream?device_id=$deviceId"
        val reqBuilder = Request.Builder().url(url)
            .header("Accept", "text/event-stream")

        if (!apiKey.isNullOrBlank()) reqBuilder.header("X-API-Key", apiKey)

        val request = reqBuilder.build()

        eventSource = okhttp3.sse.EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : okhttp3.sse.EventSourceListener() {

                // Handles `onOpen` behavior for the remote control module.
                override fun onOpen(es: okhttp3.sse.EventSource, response: Response) {
                    DjiTrace.i("[SSE] onOpen code=${response.code}")
                    onStatus("connected")
                }

                // Handles `onEvent` behavior for the remote control module.
                override fun onEvent(
                    es: okhttp3.sse.EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                DjiTrace.i("[SSE] onEvent type=$type id=$id bytes=${data.length}")
                if (type != "command") return

                try {
                    val obj = JSONObject(data)
                    val cmdType = obj.optString("cmd_type")
                    val commandId = obj.optString("command_id").ifBlank { id ?: "" }

                    val payload = obj.optJSONObject("payload")
                    DjiTrace.i("${DjiTrace.p(cmdType, commandId)} [SSE_RX] payload=${DjiTrace.json(payload)}")

                    onCommand(obj)
                } catch (t: Throwable) {
                    DjiTrace.e("[SSE] bad JSON err=${t.message} data=$data", t)
                    onStatus("Bad JSON: ${t.message}")
                }
            }

                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(
                    es: okhttp3.sse.EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    // If we are stopping, this is expected (Socket closed)
                    if (stopping) {
                        DjiTrace.i("[SSE] closed by client (stopping=true) t=${t?.javaClass?.simpleName}:${t?.message}")
                        onStatus("disconnected")
                        return
                    }

                    DjiTrace.e("[SSE] onFailure code=${response?.code} err=${t?.message}", t)
                    onStatus("error: ${t?.message}")
                }

                // Handles `onClosed` behavior for the remote control module.
                override fun onClosed(es: okhttp3.sse.EventSource) {
                    if (stopping) {
                        DjiTrace.i("[SSE] onClosed (client stop)")
                        onStatus("disconnected")
                    } else {
                        DjiTrace.w("[SSE] onClosed (server closed)")
                        onStatus("closed")
                    }
                }
            })
    }

    // Handles `stop` behavior for the remote control module.
    fun stop() {
        stopping = true
        try {
            eventSource?.cancel()   // this is the graceful shutdown signal
        } catch (_: Throwable) {
        } finally {
            eventSource = null
        }
    }
}
