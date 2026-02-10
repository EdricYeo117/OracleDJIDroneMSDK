package dji.sampleV5.aircraft.remote

import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SseCommandClient(
    private val baseUrl: String,          // e.g. http://192.168.1.49:8080
    private val deviceId: String,          // e.g. android-controller-01
    private val apiKey: String?,           // X-API-Key
    private val onCommand: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // IMPORTANT for SSE (no read timeout)
        .build()

    private var eventSource: EventSource? = null

    fun start() {
        val url = "$baseUrl/v1/drone/stream?device_id=$deviceId"
        val reqBuilder = Request.Builder().url(url)
            .addHeader("Accept", "text/event-stream")

        if (!apiKey.isNullOrBlank()) {
            reqBuilder.addHeader("X-API-Key", apiKey.trim())
        }

        val request = reqBuilder.build()

        onStatus("Connecting SSE: $url")

        eventSource = EventSources.createFactory(client)
            .newEventSource(request, object : EventSourceListener() {
                override fun onOpen(es: EventSource, response: Response) {
                    onStatus("SSE connected (${response.code})")
                }

                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    if (type == "command") {
                        try {
                            onCommand(JSONObject(data))
                        } catch (e: Exception) {
                            onStatus("Bad command JSON: ${e.message}")
                        }
                    }
                }

                override fun onClosed(es: EventSource) {
                    onStatus("SSE closed")
                }

                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    val code = response?.code
                    onStatus("SSE failure code=$code err=${t?.message}")
                    // OkHttp will retry on connection failure; you can also implement a backoff restart here
                }
            })
    }

    fun stop() {
        eventSource?.cancel()
        eventSource = null
        onStatus("SSE stopped")
    }
}
