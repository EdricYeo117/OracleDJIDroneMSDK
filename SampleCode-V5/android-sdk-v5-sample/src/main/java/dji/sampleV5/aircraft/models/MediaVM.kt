package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sampleV5.aircraft.remote.RedUploader
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.KeyTools.createKey

import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.error.RxError
import dji.v5.common.utils.CallbackUtils
import dji.v5.common.utils.RxUtil
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.*
import dji.v5.utils.common.LogUtils
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.error.DJICommonError
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DiskUtil
import dji.v5.utils.common.StringUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay


/**
 * @author feel.feng
 * @time 2022/04/20 2:19 下午
 * @description: 媒体回放下载数据
 */
class MediaVM : DJIViewModel() {
    var mediaFileListData = MutableLiveData<MediaFileListData>()
    var fileListState = MutableLiveData<MediaFileListState>()
    var isPlayBack = MutableLiveData<Boolean>()
    var componentIndex = MutableLiveData<ComponentIndexType>()

    fun init() {
        addMediaFileListStateListener()
        mediaFileListData.value = MediaDataCenter.getInstance().mediaManager.mediaFileListData
        MediaDataCenter.getInstance().mediaManager.addMediaFileListStateListener { mediaFileListState ->
            if (mediaFileListState == MediaFileListState.UP_TO_DATE) {
                val data = MediaDataCenter.getInstance().mediaManager.mediaFileListData;
                mediaFileListData.postValue(data)
            }
        }

    }

    fun destroy() {
        KeyManager.getInstance().cancelListen(this);
        removeAllFileListStateListener()

        MediaDataCenter.getInstance().mediaManager.release()
    }

    fun pullMediaFileListFromCamera(mediaFileIndex: Int, count: Int) {
        var currentTime = System.currentTimeMillis()
        MediaDataCenter.getInstance().mediaManager.pullMediaFileListFromCamera(
            PullMediaFileListParam.Builder().mediaFileIndex(mediaFileIndex).count(count).build(),
            object :
                CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("Spend time:${(System.currentTimeMillis() - currentTime) / 1000}s")
                    LogUtils.i(logTag, "fetch success")
                }

                override fun onFailure(error: IDJIError) {
                    LogUtils.e(logTag, "fetch failed$error")
                }
            })
    }

    private fun addMediaFileListStateListener() {
        MediaDataCenter.getInstance().mediaManager.addMediaFileListStateListener(object :
            MediaFileListStateListener {
            override fun onUpdate(mediaFileListState: MediaFileListState) {
                fileListState.postValue(mediaFileListState)
            }

        })
    }

    private fun removeAllFileListStateListener() {
        MediaDataCenter.getInstance().mediaManager.removeAllMediaFileListStateListener()
    }

    fun getMediaFileList(): List<MediaFile> {
        return mediaFileListData.value?.data!!
    }

    fun setMediaFileXMPCustomInfo(info: String) {
        MediaDataCenter.getInstance().mediaManager.setMediaFileXMPCustomInfo(info, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    fun getMediaFileXMPCustomInfo() {
        MediaDataCenter.getInstance().mediaManager.getMediaFileXMPCustomInfo(object :
            CommonCallbacks.CompletionCallbackWithParam<String> {
            override fun onSuccess(s: String) {
                toastResult?.postValue(DJIToastResult.success(s))
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    fun setComponentIndex(index: ComponentIndexType) {
        componentIndex.postValue(index)
        isPlayBack.postValue(false)
        KeyManager.getInstance().cancelListen(this)
        KeyManager.getInstance().listen(
            createKey(
                CameraKey.KeyIsPlayingBack, index
            ), this
        ) { _, newValue ->
            newValue?.let {
                isPlayBack.postValue(it)
            }
        }
        val mediaSource = MediaFileListDataSource.Builder().setIndexType(index).build()
        MediaDataCenter.getInstance().mediaManager.setMediaFileDataSource(mediaSource)
    }

    fun setStorage(location: CameraStorageLocation) {
        val mediaSource = MediaFileListDataSource.Builder().setLocation(location).build()
        MediaDataCenter.getInstance().mediaManager.setMediaFileDataSource(mediaSource)
    }

    fun enable() {
        MediaDataCenter.getInstance().mediaManager.enable(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.e(logTag, "enable playback success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(logTag, "error is ${error.description()}")
            }
        })
    }

    fun disable() {
        MediaDataCenter.getInstance().mediaManager.disable(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.e(logTag, "exit playback success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(logTag, "error is ${error.description()}")
            }
        })
    }

    fun takePhoto(callback: CommonCallbacks.CompletionCallback) {
        val index = componentIndex.value
        if (index == null) {
            CallbackUtils.onFailure(callback, DJICommonError.FACTORY.build(DJICommonError.DISCONNECTED))
            return
        }
        RxUtil.setValue(createKey<CameraMode>(CameraKey.KeyCameraMode, index), CameraMode.PHOTO_NORMAL)
            .andThen(RxUtil.performActionWithOutResult(createKey(CameraKey.KeyStartShootPhoto, index)))
            .subscribe({ CallbackUtils.onSuccess(callback) }
            ) { throwable: Throwable ->
                CallbackUtils.onFailure(
                    callback,
                    (throwable as RxError).djiError
                )
            }
    }

    fun formatSDCard(callback: CommonCallbacks.CompletionCallback) {
        val index = componentIndex.value
        if (index == null) {
            CallbackUtils.onFailure(callback, DJICommonError.FACTORY.build(DJICommonError.DISCONNECTED))
            return
        }
        KeyManager.getInstance().performAction(createKey(CameraKey.KeyFormatStorage, index), CameraStorageLocation.SDCARD, object :
            CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                callback.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback.onFailure(error)
            }

        })
    }

    fun downloadMediaFile(mediaList: ArrayList<MediaFile>) {
        mediaList.forEach {
            downloadFile(it)
        }
    }

    private fun downloadFile(mediaFile: MediaFile) {
        val dirs = File(DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), "/mediafile"))
        if (!dirs.exists()) {
            dirs.mkdirs()
        }
        val filepath = DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), "/mediafile/" + mediaFile.fileName)
        val file = File(filepath)
        var offset = 0L
        val outputStream = FileOutputStream(file, true)
        val bos = BufferedOutputStream(outputStream)
        mediaFile.pullOriginalMediaFileFromCamera(offset, object : MediaFileDownloadListener {
            override fun onStart() {
                LogUtils.i("MediaFile", "${mediaFile.fileIndex} start download")
            }

            override fun onProgress(total: Long, current: Long) {
                val fullSize = offset + total;
                val downloadedSize = offset + current
                val data: Double = StringUtils.formatDouble((downloadedSize.toDouble() / fullSize.toDouble()))
                val result: String = StringUtils.formatDouble(data * 100, "#0").toString() + "%"
                LogUtils.i("MediaFile", "${mediaFile.fileIndex}  progress $result")
            }

            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                try {
                    bos.write(data)
                    bos.flush()
                } catch (e: IOException) {
                    LogUtils.e("MediaFile", "write error" + e.message)
                }
            }

            override fun onFinish() {
                try {
                    outputStream.close()
                    bos.close()
                } catch (error: IOException) {
                    LogUtils.e("MediaFile", "close error$error")
                }
                LogUtils.i("MediaFile", "${mediaFile.fileIndex}  download finish")
            }

            override fun onFailure(error: IDJIError?) {
                LogUtils.e("MediaFile", "download error$error")
            }

        })
    }
    // New function to: take photo -> download newest -> upload to RED
    /**
     * Take a photo, download the newest photo file to /mediafile, then upload it to RED.
     *
     * @param redUploadUrl example: "http://<UBUNTU_IP>:1880/upload"
     * @param callback returns success/failure with DJI error description
     */
    fun takePhotoThenDownloadThenUpload(
        redUploadUrl: String,
        callback: CommonCallbacks.CompletionCallback
    ) {
        // Step 1: take photo
        takePhoto(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                // Step 2: after capture, refresh file list and locate newest
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val newest = waitForNewestMediaFileOrNull(timeoutMs = 12_000L)
                        if (newest == null) {
                            CallbackUtils.onFailure(
                                callback,
                                DJICommonError.FACTORY.build(DJICommonError.UNKNOWN)
                            )
                            return@launch
                        }

                        // Step 3: download newest file (async callback)
                        downloadFileWithCallback(newest,
                            onSuccess = { downloadedFile ->
                                // Step 4: upload to RED off main thread
                                CoroutineScope(Dispatchers.IO).launch {
                                    val (ok, errMsg) = try {
                                        RedUploader.uploadFile(redUploadUrl, downloadedFile)
                                    } catch (t: Throwable) {
                                        false to (t.message ?: "upload exception")
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (ok) {
                                            CallbackUtils.onSuccess(callback)
                                        } else {
                                            CallbackUtils.onFailure(
                                                callback,
                                                DJICommonError.FACTORY.build(DJICommonError.UNKNOWN)
                                            )
                                            // If you want better error propagation, also toast/log errMsg:
                                            LogUtils.e(logTag, "Upload failed: $errMsg")
                                        }
                                    }
                                }
                            },
                            onFailure = { djiError ->
                                CallbackUtils.onFailure(callback, djiError)
                            }
                        )
                    } catch (t: Throwable) {
                        LogUtils.e(logTag, "takePhotoThenDownloadThenUpload exception: ${t.message}")
                        CallbackUtils.onFailure(
                            callback,
                            DJICommonError.FACTORY.build(DJICommonError.UNKNOWN)
                        )
                    }
                }
            }

            override fun onFailure(error: IDJIError) {
                CallbackUtils.onFailure(callback, error)
            }
        })
    }

    /**
     * Downloads a single MediaFile into /mediafile/<filename> and returns the File via callback.
     * This is a "callback-friendly" version of your existing downloadFile().
     */
    private fun downloadFileWithCallback(
        mediaFile: MediaFile,
        onSuccess: (File) -> Unit,
        onFailure: (IDJIError) -> Unit
    ) {
        val dirs = File(DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), "/mediafile"))
        if (!dirs.exists()) dirs.mkdirs()

        val filepath = DiskUtil.getExternalCacheDirPath(
            ContextUtil.getContext(),
            "/mediafile/" + mediaFile.fileName
        )
        val file = File(filepath)

        var offset = 0L
        val outputStream = FileOutputStream(file, true)
        val bos = BufferedOutputStream(outputStream)

        mediaFile.pullOriginalMediaFileFromCamera(offset, object : MediaFileDownloadListener {
            override fun onStart() {
                LogUtils.i("MediaFile", "${mediaFile.fileIndex} start download -> $filepath")
            }

            override fun onProgress(total: Long, current: Long) {
                val fullSize = offset + total
                val downloadedSize = offset + current
                val data: Double = StringUtils.formatDouble((downloadedSize.toDouble() / fullSize.toDouble()))
                val result: String = StringUtils.formatDouble(data * 100, "#0").toString() + "%"
                LogUtils.i("MediaFile", "${mediaFile.fileIndex} progress $result")
            }

            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                try {
                    bos.write(data)
                    bos.flush()
                } catch (e: IOException) {
                    LogUtils.e("MediaFile", "write error ${e.message}")
                }
            }

            override fun onFinish() {
                try {
                    outputStream.close()
                    bos.close()
                } catch (e: IOException) {
                    LogUtils.e("MediaFile", "close error ${e.message}")
                }
                LogUtils.i("MediaFile", "${mediaFile.fileIndex} download finish -> $filepath")
                onSuccess(file)
            }

            override fun onFailure(error: IDJIError?) {
                try {
                    outputStream.close()
                    bos.close()
                } catch (_: IOException) {}
                val err = error ?: DJICommonError.FACTORY.build(DJICommonError.UNKNOWN)
                LogUtils.e("MediaFile", "download error $err")
                onFailure(err)
            }
        })
    }

    /**
     * Waits for the media file list to update after taking a photo and returns the newest MediaFile.
     *
     * How it works:
     * - Pulls the latest list repeatedly (polling) until it sees a newest item.
     * - Uses mediaFileListData (already present) as source of truth.
     *
     * Assumption:
     * - Newest file appears at index 0 or has the largest fileIndex.
     *   DJI ordering can vary by camera/firmware; we handle both.
     */
    private suspend fun waitForNewestMediaFileOrNull(timeoutMs: Long): MediaFile? {
        val start = System.currentTimeMillis()

        // Snapshot baseline: current "newest" identity
        val baseline = mediaFileListData.value?.data
        val baselineTopName = baseline?.firstOrNull()?.fileName
        val baselineMaxIndex = baseline?.maxOfOrNull { it.fileIndex } ?: -1

        while (System.currentTimeMillis() - start < timeoutMs) {
            // Trigger a refresh pull (small batch from 0)
            // If your camera has huge lists, pulling 20 is usually enough for newest.
            pullMediaFileListFromCamera(mediaFileIndex = 0, count = 20)

            // Give time for async update
            delay(500)

            val list = mediaFileListData.value?.data
            if (list.isNullOrEmpty()) continue

            // Candidate 1: list[0] changed
            val top = list.firstOrNull()
            if (top != null && top.fileName != baselineTopName) return top

            // Candidate 2: any fileIndex greater than baseline max
            val max = list.maxByOrNull { it.fileIndex }
            if (max != null && max.fileIndex > baselineMaxIndex) return max
        }

        return null
    }
}