import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
private var previewSurface: Surface? = null
private var w = 0
private var h = 0

private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
private val scaleType = ICameraStreamManager.ScaleType.CENTER_CROP

fun setupPreview(surfaceView: SurfaceView) {
    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width
            h = height
            previewSurface = holder.surface

            // Show camera stream on this surface
            cameraStreamManager.putCameraStreamSurface(
                cameraIndex,
                holder.surface,
                w,
                h,
                scaleType
            )
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            cameraStreamManager.removeCameraStreamSurface(holder.surface)
            previewSurface = null
        }
    })
}