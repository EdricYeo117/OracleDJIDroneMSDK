package dji.sampleV5.aircraft.remote

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
    private var eventSource: EventSource? = null

    fun start() {
        stop()

        val url = "${baseUrl.trimEnd('/')}/v1/drone/stream?device_id=$deviceId"
        val reqBuilder = Request.Builder().url(url)
        if (!apiKey.isNullOrBlank()) reqBuilder.header("X-API-Key", apiKey)

        val req = reqBuilder.build()

        DjiTrace.i("[SSE] start url=$url device_id=$deviceId apiKey=${if (apiKey.isNullOrBlank()) "none" else "set"}")
        onStatus("SSE connecting…")

        val factory = EventSources.createFactory(okHttpClient)
        eventSource = factory.newEventSource(req, object : EventSourceListener() {
            override fun onOpen(es: EventSource, response: Response) {
                DjiTrace.i("[SSE] onOpen code=${response.code} msg=${response.message}")
                onStatus("SSE connected (${response.code})")
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
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

            override fun onClosed(es: EventSource) {
                DjiTrace.w("[SSE] onClosed")
                onStatus("SSE closed")
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                DjiTrace.e("[SSE] onFailure code=${response?.code} err=${t?.message}", t)
                onStatus("SSE failure code=${response?.code} err=${t?.message}")
            }
        })
    }

    fun stop() {
        if (eventSource != null) {
            DjiTrace.w("[SSE] stop (cancel)")
            eventSource?.cancel()
            eventSource = null
        }
    }
}
