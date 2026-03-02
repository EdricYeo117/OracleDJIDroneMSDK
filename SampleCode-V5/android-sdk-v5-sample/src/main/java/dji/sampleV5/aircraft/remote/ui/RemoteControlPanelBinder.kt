package dji.sampleV5.aircraft.remote.ui

/**
 * Remote module file `ui/RemoteControlPanelBinder.kt`: contains RemoteControlPanelBinder implementation details.
 */

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.models.MediaVM
import dji.sampleV5.aircraft.remote.NetworkInfo
import dji.sampleV5.aircraft.remote.PythonServerConfigStore
import dji.sampleV5.aircraft.remote.RemoteCommandService
import dji.sampleV5.aircraft.remote.RemoteServiceController
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class RemoteControlPanelBinder(
    private val root: View,
    private val appContext: Context,
    private val mediaVM: MediaVM
) {

    private val tvStatus: TextView = root.findViewById(R.id.tv_remote_status)
    private val tvIp: TextView = root.findViewById(R.id.tv_remote_ip)

    // NEW: python server host/port
    private val etPythonHost: EditText = root.findViewById(R.id.et_python_host)
    private val etPythonPort: EditText = root.findViewById(R.id.et_python_port)

    // Existing: upload URL (we will derive it)
    private val etUrl: EditText = root.findViewById(R.id.et_red_upload_url)

    companion object {
        // Change this to your real python upload route
        private const val UPLOAD_PATH = "/v1/drone/uploads/photo"
        private const val DEFAULT_PY_HOST = "192.168.1.49"
        private const val DEFAULT_PY_PORT = 8080
    }

    // Handles `buildUploadUrl` behavior for the remote control module.
    private fun buildUploadUrl(host: String, port: Int): String {
        val safeHost = host.trim()
        val safePort = port.coerceIn(1, 65535)
        return "http://$safeHost:$safePort$UPLOAD_PATH"
    }

    // Handles `bind` behavior for the remote control module.
    fun bind() {
        // Controller listener (local)
        val ip = NetworkInfo.getLocalIpv4() ?: "Unknown"
        tvIp.text = "Controller IP: $ip"

        // Load saved python server config
        val cfg = PythonServerConfigStore.get(appContext)

        if (etPythonHost.text.isNullOrBlank()) etPythonHost.setText(cfg.host)
        if (etPythonPort.text.isNullOrBlank()) etPythonPort.setText(cfg.port.toString())

        // Derive upload URL from host/port (you can still allow manual edits if desired)
        if (etUrl.text.isNullOrBlank()) {
            etUrl.setText(buildUploadUrl(cfg.host, cfg.port))
        }

        // Save python server host/port
        root.findViewById<Button>(R.id.btn_save_python_server).setOnClickListener {
            val host = etPythonHost.text?.toString()?.trim().orEmpty()
            val port = etPythonPort.text?.toString()?.trim()?.toIntOrNull()

            if (host.isEmpty()) {
                ToastUtils.showToast("Python server IP cannot be empty")
                return@setOnClickListener
            }
            if (port == null || port !in 1..65535) {
                ToastUtils.showToast("Invalid port")
                return@setOnClickListener
            }

            PythonServerConfigStore.set(appContext, host, port)
            etUrl.setText(buildUploadUrl(host, port))

            ToastUtils.showToast("Saved Python server: $host:$port")
            refreshStatus()
        }

        // Start/Stop controller listener
        root.findViewById<Button>(R.id.btn_remote_start).setOnClickListener {
            RemoteServiceController.start(appContext)
            ToastUtils.showToast("Remote listener started")
            refreshStatus()
        }

        root.findViewById<Button>(R.id.btn_remote_stop).setOnClickListener {
            RemoteServiceController.stop(appContext)
            ToastUtils.showToast("Remote listener stopped")
            refreshStatus()
        }

        // Photo + upload (uses derived URL unless user overwrote it)
        root.findViewById<Button>(R.id.btn_photo_upload).setOnClickListener {
            val host = etPythonHost.text?.toString()?.trim().orEmpty()
            val port = etPythonPort.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_PY_PORT

            if (host.isEmpty()) {
                ToastUtils.showToast("Enter Python server IP")
                return@setOnClickListener
            }

            // Prefer explicit URL field if user manually edited it, else derive it
            val url = etUrl.text?.toString()?.trim().orEmpty()
                .ifEmpty { buildUploadUrl(host, port) }

            if (url.isEmpty()) {
                ToastUtils.showToast("Upload URL is empty")
                return@setOnClickListener
            }

            mediaVM.takePhotoThenDownloadThenUpload(url, object : CommonCallbacks.CompletionCallback {
                // Handles `onSuccess` behavior for the remote control module.
                override fun onSuccess() {
                    ToastUtils.showToast("Photo uploaded")
                }

                // Handles `onFailure` behavior for the remote control module.
                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("Upload failed: ${error.description()}")
                }
            })
        }

        refreshStatus()
    }

    // Handles `refreshStatus` behavior for the remote control module.
    private fun refreshStatus() {
        val running = RemoteCommandService.isRunning
        val host = etPythonHost.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_PY_HOST }
        val port = etPythonPort.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_PY_PORT

        tvStatus.text =
            "Status: " + (if (running) "Running" else "Stopped") +
                    " | Python: $host:$port"
    }
}
