package dji.sampleV5.aircraft.pages

import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.models.MediaVM
import android.content.Intent
import dji.sampleV5.aircraft.remote.ui.RemoteControlPanelBinder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragVirtualStickPageBinding
import dji.sampleV5.aircraft.keyvalue.KeyValueDialogUtil
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.remote.DefaultAircraftControlFacade
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.utils.common.JsonUtil
import kotlin.math.abs
import dji.sampleV5.aircraft.remote.DroneCommandBridge
import dji.sampleV5.aircraft.remote.DefaultVirtualStickFacade
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/5/11
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickFragment : DJIFragment() {

    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val mediaVM: MediaVM by activityViewModels()
    private var binding: FragVirtualStickPageBinding? = null
    private val deviation: Double = 0.02
    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
    private val previewCameraIndex = ComponentIndexType.LEFT_OR_MAIN
    private val previewScaleType = ICameraStreamManager.ScaleType.CENTER_CROP
    private var previewSurface: Surface? = null

    // Functions to help camera preview
    private fun setupCameraPreview() {
        val tv = binding?.cameraPreviewView ?: return

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(st)
                previewSurface = surface

                // avoid odd sizes; some pipelines behave better with multiples of 16
                val w16 = (width / 16) * 16
                val h16 = (height / 16) * 16

                cameraStreamManager.putCameraStreamSurface(
                    previewCameraIndex,
                    surface,
                    w16,
                    h16,
                    previewScaleType
                )
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                previewSurface?.let { surface ->
                    val w16 = (width / 16) * 16
                    val h16 = (height / 16) * 16
                    cameraStreamManager.putCameraStreamSurface(
                        previewCameraIndex,
                        surface,
                        w16,
                        h16,
                        previewScaleType
                    )
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                previewSurface?.let { surface ->
                    cameraStreamManager.removeCameraStreamSurface(surface)
                    surface.release()
                }
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragVirtualStickPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Remote panel (only if included in XML)
//        val panelRoot = view.findViewById<View?>(R.id.remote_control_panel)
//        if (panelRoot != null) {
//            RemoteControlPanelBinder(
//                root = panelRoot,
//                appContext = requireContext().applicationContext,
//                mediaVM = mediaVM
//            ).bind()
//        }

        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)
        initBtnClickListener()
        initStickListener()
        // Set up camera preview
        setupCameraPreview()

        virtualStickVM.listenRCStick()

        virtualStickVM.currentSpeedLevel.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.useRcStick.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) { updateVirtualStickInfo() }
        virtualStickVM.virtualStickAdvancedParam.observe(viewLifecycleOwner) { updateVirtualStickInfo() }

        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.text = it
        }
        DroneCommandBridge.bindVirtualStickFacade(
            DefaultVirtualStickFacade(virtualStickVM)
        )
        DroneCommandBridge.bindAircraftControlFacade(
            DefaultAircraftControlFacade(
                basicAircraftControlVM
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        DroneCommandBridge.unbindVirtualStickFacade()
        DroneCommandBridge.unbindAircraftControlFacade()
        previewSurface?.let { surface ->
            cameraStreamManager.removeCameraStreamSurface(surface)
            surface.release()
        }
        previewSurface = null
        binding = null
    }

    private fun initBtnClickListener() {
        binding?.btnEnableVirtualStick?.setOnClickListener {
            virtualStickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("enableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("enableVirtualStick error,$error")
                }
            })
        }
        binding?.btnDisableVirtualStick?.setOnClickListener {
            virtualStickVM.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("disableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("disableVirtualStick error,${error})")
                }
            })
        }
        binding?.btnSetVirtualStickSpeedLevel?.setOnClickListener {
            val speedLevels = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            initPopupNumberPicker(Helper.makeList(speedLevels)) {
                virtualStickVM.setSpeedLevel(speedLevels[indexChosen[0]])
                resetIndex()
            }
        }
        binding?.btnTakeOff?.setOnClickListener {
            basicAircraftControlVM.startTakeOff(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start takeOff onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start takeOff onFailure,$error")
                }
            })
        }
        binding?.btnLanding?.setOnClickListener {
            basicAircraftControlVM.startLanding(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start landing onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start landing onFailure,$error")
                }
            })
        }
        binding?.btnUseRcStick?.setOnClickListener {
            virtualStickVM.useRcStick.value = virtualStickVM.useRcStick.value != true
            if (virtualStickVM.useRcStick.value == true) {
                ToastUtils.showToast(
                    "After it is turned on," +
                            "the joystick value of the RC will be used as the left/ right stick value"
                )
            }
        }

        binding?.btnOpenRemoteAutomation?.setOnClickListener {
            childFragmentManager.beginTransaction()
                .replace(R.id.remote_feature_container, RemoteAutomationFragment())
                .addToBackStack("RemoteAutomation")
                .commit()
        }

        binding?.btnSetVirtualStickAdvancedParam?.setOnClickListener {
            KeyValueDialogUtil.showInputDialog(
                activity, "Set Virtual Stick Advanced Param",
                JsonUtil.toJson(virtualStickVM.virtualStickAdvancedParam.value), "", false
            ) {
                it?.apply {
                    val param = JsonUtil.toBean(this, VirtualStickFlightControlParam::class.java)
                    if (param == null) {
                        ToastUtils.showToast("Value Parse Error")
                        return@showInputDialog
                    }
                    virtualStickVM.virtualStickAdvancedParam.postValue(param)
                }
            }
        }
        binding?.btnSendVirtualStickAdvancedParam?.setOnClickListener {
            virtualStickVM.virtualStickAdvancedParam.value?.let {
                virtualStickVM.sendVirtualStickAdvancedParam(it)
            }
        }
        binding?.btnEnableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.enableVirtualStickAdvancedMode()
        }
        binding?.btnDisableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.disableVirtualStickAdvancedMode()
        }
    }

    private fun initStickListener() {
        binding?.leftStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

                if (abs(pX) >= deviation) {
                    leftPx = pX
                }

                if (abs(pY) >= deviation) {
                    leftPy = pY
                }

                virtualStickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        binding?.rightStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

                if (abs(pX) >= deviation) {
                    rightPx = pX
                }

                if (abs(pY) >= deviation) {
                    rightPy = pY
                }

                virtualStickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
    }

    private fun updateVirtualStickInfo() {
        val builder = StringBuilder()
        builder.append("Speed level:").append(virtualStickVM.currentSpeedLevel.value)
        builder.append("\n")
        builder.append("Use rc stick as virtual stick:").append(virtualStickVM.useRcStick.value)
        builder.append("\n")
        builder.append("Is virtual stick enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable)
        builder.append("\n")
        builder.append("Current control permission owner:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.currentFlightControlAuthorityOwner)
        builder.append("\n")
        builder.append("Change reason:").append(virtualStickVM.currentVirtualStickStateInfo.value?.reason)
        builder.append("\n")
        builder.append("Rc stick value:").append(virtualStickVM.stickValue.value?.toString())
        builder.append("\n")
        builder.append("Is virtual stick advanced mode enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled)
        builder.append("\n")
        builder.append("Virtual stick advanced mode param:").append(virtualStickVM.virtualStickAdvancedParam.value?.toJson())
        builder.append("\n")
        mainHandler.post {
            binding?.virtualStickInfoTv?.text = builder.toString()
        }
    }
}