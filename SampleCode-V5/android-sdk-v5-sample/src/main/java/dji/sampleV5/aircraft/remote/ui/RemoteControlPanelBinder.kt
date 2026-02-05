package dji.sampleV5.aircraft.remote.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.models.MediaVM
import dji.sampleV5.aircraft.remote.NetworkInfo
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
    private val etUrl: EditText = root.findViewById(R.id.et_red_upload_url)

    fun bind() {
        // IP display
        val ip = NetworkInfo.getLocalIpv4() ?: "Unknown"
        tvIp.text = "IP: $ip, Port: ${RemoteCommandService.DEFAULT_PORT}"

        // Start/Stop
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

        // Photo + upload
        root.findViewById<Button>(R.id.btn_photo_upload).setOnClickListener {
            val url = etUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                ToastUtils.showToast("Enter RED upload URL")
                return@setOnClickListener
            }

            mediaVM.takePhotoThenDownloadThenUpload(url, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("Photo uploaded")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("Upload failed: ${error.description()}")
                }
            })
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        tvStatus.text = "Status: " + if (RemoteCommandService.isRunning) "Running" else "Stopped"
    }
}
