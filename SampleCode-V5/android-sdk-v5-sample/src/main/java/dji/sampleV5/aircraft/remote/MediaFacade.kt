// File: SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/remote/MediaFacade.kt
package dji.sampleV5.aircraft.remote

/**
 * This facade wraps your existing MediaVM.
 * You implement takePhoto -> download -> upload-to-RED inside the MediaVM wrapper.
 */
interface MediaFacade {
    fun takePhotoAndUpload(uploadUrl: String, cb: (Boolean, String?) -> Unit)
}
