package com.omkod.easycam

import android.Manifest.permission.CAMERA
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
import android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
import android.hardware.camera2.CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER
import android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE
import android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER
import android.hardware.camera2.CaptureRequest.CONTROL_MODE
import android.hardware.camera2.CaptureRequest.FLASH_MODE
import android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF
import android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION
import android.hardware.camera2.CaptureResult.CONTROL_AE_STATE
import android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
import android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_INACTIVE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Size
import android.view.*
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.view_camera.view.*
import timber.log.Timber
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt


class CameraView : ConstraintLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private val manager: CameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
    private val cameraOpenCloseLock = Semaphore(1)

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var lastScreenBrightness: Float = 0f
    private var isFlushAvailable = true
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var captureState: Int = STATE_PREVIEW
    private var isEnableFlush = false
    private var isFaceCamera = false
    private var cameraId = ""
    private var photoResolitions: List<Size> = emptyList()
    private var previewResolitions: List<Size> = emptyList()
    private var previewSize: Size = Size(1, 1)
    private var photoSize: Size = Size(1, 1)
    private var waitingFrames: Int = 0
    private var previousFingerSpacing = 0f
    private var camScaler = CamScalier(1f, 1, 1)
    private var isInFocusing = false
    private var isZoomEnabled = true

    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }
    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (captureState) {
                STATE_WAITING_LOCK -> handleWaitingLockResult(result)
                STATE_WAITING_PRECAPTURE -> handleWaitingPrecaptureResult(result)
                STATE_WAITING_NON_PRECAPTURE -> handleWaitingNonPrecaptureResult(result)
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }
    }
    private var orientationListener: OrientationListener

    var photoCallback: ((bitmap: Bitmap) -> Unit)? = null
    var resolutionChangeCallback: ((faceResolutionIndex: Int, backResolutionIndex: Int) -> Unit)? = null
    var maxPixelCount = Long.MAX_VALUE
    var currentFaceResolutionIndex = -1
    var currentBackResolutionIndex = -1


    init {
        inflate(context, R.layout.view_camera, this)
        cameraFlashView.setOnClickListener { toggleFlash() }
        cameraSwitchView.setOnClickListener { switchCamera() }
        cameraSettings.setOnClickListener { showResolutionsDialog() }
        cameraActionView.setOnClickListener { lockFocus() }
        cameraInfo.setOnClickListener { showInfoDialog() }
        orientationListener = OrientationListener(context)
    }

    private fun showInfoDialog() {
        val dlgAlert = AlertDialog.Builder(context)
        dlgAlert.setMessage("Screen size - $height x $width\n"
                + "Photo sizes - $photoResolitions\n"
                + "Photo size - $photoSize\n"
                + "Preview sizes - $previewResolitions\n"
                + "Preview size - $previewSize\n"
        )
        dlgAlert.setTitle("Camera Info")
        dlgAlert.setPositiveButton("OK", null)
        dlgAlert.setCancelable(true)
        dlgAlert.create().show()
    }

    fun setSettingsVisibility(visible: Boolean) {
        cameraSettings.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun setZoomEnabled(enabled: Boolean) {
        isZoomEnabled = enabled
    }

    fun setInitialCamera(isFaceCamera: Boolean) {
        this.isFaceCamera = isFaceCamera
    }

    fun startCamera() {
        try {
            orientationListener.enable()
            isInFocusing = false
            startBackgroundThread()
            initCameraID()
            initResolutions()
            initPhotoSize()
            initImageReader()

            if (cameraView.isAvailable) {
                openCamera()
            } else {
                cameraView.surfaceTextureListener = surfaceTextureListener
            }
        } catch (t: Throwable) {
            Timber.e(t)
            Toast.makeText(context, "Не удалось запустить камеру!", Toast.LENGTH_LONG).show()
        }
    }

    fun stopCamera() {
        orientationListener.disable()
        try {
            cameraOpenCloseLock.acquire()
            unlockFocus()
            cameraDevice?.let { camera ->
                captureSession?.let { session ->
                    session.stopRepeating()
                    session.abortCaptures()
                    session.close()
                    captureSession = null
                }
                camera.close()
                cameraDevice = null
            }
            imageReader?.let {
                it.close()
                imageReader = null
            }
            backgroundThread?.let {
                it.quitSafely()
                try {
                    it.join()
                    backgroundThread = null
                    backgroundHandler = null
                } catch (e: InterruptedException) {
                    Timber.e(e)
                }
            }
        } catch (e: InterruptedException) {
            throw java.lang.RuntimeException("Interrupted while trying to lock camera closing.", e)
        } catch (e: Throwable) {
            Timber.e(e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun initCameraID() {
        cameraId = ""
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            isFlushAvailable = chars.get(FLASH_INFO_AVAILABLE) ?: false
            if (isFaceCamera && chars.get(LENS_FACING) == LENS_FACING_FRONT) {
                cameraId = id
                break
            } else if (!isFaceCamera && chars.get(LENS_FACING) == LENS_FACING_BACK) {
                cameraId = id
                break
            }
        }
    }

    private fun initResolutions() {
        val configuration = manager
                .getCameraCharacteristics(cameraId)
                .get(SCALER_STREAM_CONFIGURATION_MAP)
        if (configuration != null) {
            photoResolitions = configuration.getOutputSizes(ImageFormat.JPEG)
                    .sortedByDescending { s -> s.height * s.width }
            previewResolitions = configuration.getOutputSizes(SurfaceTexture::class.java)
                    .sortedByDescending { s -> s.height * s.width }
        } else {
            Timber.e("Failed to determine camera specifications")
        }
    }

    private fun initPhotoSize() {
        val photoSizeIndex = getCurrentResolutionIndex()
        setCurrentResolutionIndex(photoSizeIndex)
        photoSize = photoResolitions[photoSizeIndex]
    }

    private fun initImageReader() {
        imageReader = ImageReader
                .newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, MAX_JPEG_IMAGES)
                .apply {
                    setOnImageAvailableListener(
                            { reader ->
                                try {
                                    photoCallback?.let {
                                        post(ImageSaver(
                                                reader.acquireNextImage(),
                                                isFaceCamera,
                                                orientationListener.currentOrientation,
                                                it)

                                        )
                                    }
                                    unlockFocus()
                                } catch (e: Throwable) {
                                    Timber.e(e)
                                }
                            },
                            null)
                }
    }

    private fun initPreviewSize() {
        val photoRatio = photoSize.height.toFloat() / photoSize.width.toFloat()
        var sizes = previewResolitions
                .filter { size ->
                    abs(size.height.toFloat() / size.width.toFloat() - photoRatio) < 0.1f
                            && photoSize.height >= size.height
                            && photoSize.width >= size.width
                }
                .sortedByDescending { size -> size.height * size.width }
        if (sizes.isEmpty()) {
            sizes = previewResolitions
                    .sortedByDescending { size -> size.height * size.width }
        }
        val orientation = resources.configuration.orientation
        var size = sizes[0]
        for (element in sizes) {
            size = element
            if ((orientation == ORIENTATION_LANDSCAPE && size.height <= height && size.width <= width)
                    || (orientation == ORIENTATION_PORTRAIT && size.height <= width && size.width <= height)) {
                break
            }
        }

        if (orientation == ORIENTATION_LANDSCAPE) {
            cameraView.setAspectRatio(size.width, size.height)
        } else {
            cameraView.setAspectRatio(size.height, size.width)
        }
        previewSize = size
        initZoom()
    }

    private fun openCamera() {
        val activity = getActivity() ?: return
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            if (ContextCompat.checkSelfPermission(activity, CAMERA) != PERMISSION_GRANTED) {
                Timber.i("No camera permissions")
                throw RuntimeException("No camera permissions")
            }
            initPreviewSize()
            manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            cameraOpenCloseLock.release()
                            cameraDevice = device
                            createCameraPreviewSession()
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            cameraOpenCloseLock.release()
                            device.close()
                            cameraDevice = null
                        }

                        override fun onClosed(camera: CameraDevice) {
                            super.onClosed(camera)
                            cameraDevice = null
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            cameraOpenCloseLock.release()
                            device.close()
                            cameraDevice = null
                        }
                    },
                    null)
        } catch (e: CameraAccessException) {
            Timber.e(e)
        } catch (e: InterruptedException) {
            Timber.e(e)
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun showResolutionsDialog() {
        val resolutions: Array<CharSequence> = photoResolitions
                .map { s -> "${s.width} - ${s.height}" }
                .toTypedArray()
        AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.select_resolution))
                .setSingleChoiceItems(resolutions, getCurrentResolutionIndex()) { dialog, which ->
                    changeResolutionInDialog(which, dialog)
                }
                .create()
                .show()
    }

    private fun getCurrentResolutionIndex(): Int {
        var currentCameraResolutionIndex = if (!isFaceCamera) {
            currentBackResolutionIndex
        } else {
            currentFaceResolutionIndex
        }
        if (currentCameraResolutionIndex < 0 || currentCameraResolutionIndex >= photoResolitions.size) {
            currentCameraResolutionIndex = -1
        }
        if (currentCameraResolutionIndex == -1) {
            if (maxPixelCount > 0 && maxPixelCount < Long.MAX_VALUE) {
                currentCameraResolutionIndex = photoResolitions.indexOfFirst { size: Size ->
                    size.height * size.width <= maxPixelCount
                }
            }
        }
        if (currentCameraResolutionIndex == -1) {
            currentCameraResolutionIndex = 0
        }
        return currentCameraResolutionIndex
    }

    private fun changeResolutionInDialog(which: Int, dialog: DialogInterface) {
        setCurrentResolutionIndex(which)
        stopCamera()
        startCamera()
        dialog.dismiss()
    }

    private fun setCurrentResolutionIndex(which: Int) {
        if (!isFaceCamera) {
            currentBackResolutionIndex = which
        } else {
            currentFaceResolutionIndex = which
        }
        resolutionChangeCallback?.let {
            it(currentFaceResolutionIndex, currentBackResolutionIndex)
        }
    }

    private fun switchCamera() {
        stopCamera()
        isFaceCamera = !isFaceCamera
        startCamera()
    }

    private fun lockFocus() {
        synchronized(isInFocusing) {
            if (!isInFocusing)
                previewRequestBuilder?.let { builder ->
                    captureSession?.let { session ->
                        if (isFaceCamera && isEnableFlush && !isFlushAvailable) {
                            toggleOnWhiteScreenFlush()
                            this.postDelayed({ startLockFocus(builder, session) }, 1000)
                        } else {
                            startLockFocus(builder, session)
                        }
                    }
                }
        }
    }

    private fun startLockFocus(builder: CaptureRequest.Builder, session: CameraCaptureSession) {
        try {
            isInFocusing = true
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            builder.set(FLASH_MODE, getFlushMode())
            waitingFrames = 0
            captureState = STATE_WAITING_LOCK
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
        } catch (e: CameraAccessException) {
            Timber.e(e)
            isInFocusing = false
        }
    }

    private fun toggleOnWhiteScreenFlush() {
        val window: Window = getActivity()!!.window
        val params: WindowManager.LayoutParams = window.attributes

        lastScreenBrightness = params.screenBrightness
        params.screenBrightness = 1f
        window.attributes = params
        vFronFlush.visibility = VISIBLE
    }

    private fun runPreCaptureSequence() {
        previewRequestBuilder?.let { builder ->
            captureSession?.let { session ->
                builder.set(CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_START)
                captureState = STATE_WAITING_PRECAPTURE
                builder.set(SCALER_CROP_REGION, camScaler.currentView);
                session.capture(builder.build(), captureCallback, backgroundHandler)

                builder.set(CONTROL_AE_PRECAPTURE_TRIGGER, null)
                builder.set(FLASH_MODE, getFlushMode())
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            }
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture: SurfaceTexture = cameraView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)
            cameraDevice?.let { camera ->
                imageReader?.let { reader ->
                    camera.createCaptureSession(listOf(surface, reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                    captureSession = cameraCaptureSession
                                    try {
                                        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surface)
                                            set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                            set(SCALER_CROP_REGION, camScaler.currentView)
                                            val request = build()
                                            cameraCaptureSession.setRepeatingRequest(request, captureCallback, backgroundHandler)
                                        }
                                    } catch (e: CameraAccessException) {
                                        Timber.e(e)
                                    }
                                }

                                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                    Timber.e("Failed")
                                }

                                override fun onClosed(session: CameraCaptureSession) {
                                    super.onClosed(session)
                                    captureSession = null
                                }
                            }, backgroundHandler
                    )
                }
            }
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    private fun handleWaitingNonPrecaptureResult(result: CaptureResult) {
        val aeState = result.get(CONTROL_AE_STATE)
        if (aeState == null
                || aeState != CONTROL_AE_STATE_PRECAPTURE
                || waitingFrames >= MAX_WAITING_FRAMES) {
            captureState = STATE_PICTURE_TAKEN
            waitingFrames = 0
            captureStillPicture()
        } else {
            waitingFrames++
        }
    }

    private fun handleWaitingPrecaptureResult(result: CaptureResult) {
        val aeState = result.get(CONTROL_AE_STATE)
        if (aeState == null || waitingFrames >= MAX_WAITING_FRAMES ||
                aeState == CONTROL_AE_STATE_PRECAPTURE ||
                aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
            captureState = STATE_WAITING_NON_PRECAPTURE
            waitingFrames = 0
        } else {
            waitingFrames++
        }
    }

    private fun handleWaitingLockResult(result: CaptureResult) {
        val afState = result.get(CONTROL_AF_STATE)
        if (afState == null) {
            waitingFrames = 0
            captureStillPicture()
        } else if (CONTROL_AF_STATE_FOCUSED_LOCKED == afState
                || CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState
                || CONTROL_AF_STATE_PASSIVE_UNFOCUSED == afState
                || CONTROL_AF_STATE_PASSIVE_FOCUSED == afState
        ) {
            waitingFrames = 0
            val aeState = result.get(CONTROL_AE_STATE)
            if (aeState == null || aeState == CONTROL_AE_STATE_CONVERGED) {
                captureState = STATE_PICTURE_TAKEN
                captureStillPicture()
            } else {
                runPreCaptureSequence()
            }
        } else if (CONTROL_AF_STATE_INACTIVE == afState && waitingFrames >= MAX_WAITING_FRAMES) {
            waitingFrames = 0
            captureState = STATE_PICTURE_TAKEN
            captureStillPicture()
        } else {
            waitingFrames++
        }
    }

    private fun captureStillPicture() {
        captureSession?.let { session ->
            cameraDevice?.let { camera ->
                imageReader?.let { reader ->
                    val captureBuilder = camera.createCaptureRequest(TEMPLATE_STILL_CAPTURE)
                    captureBuilder.addTarget(reader.surface)
                    captureBuilder.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    captureBuilder.set(FLASH_MODE, getFlushMode())
                    captureBuilder.set(SCALER_CROP_REGION, camScaler.currentView);
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        session.stopRepeating()
                        session.abortCaptures()
                        Thread.sleep(100)
                    }
                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        }
                    }, backgroundHandler)
                }
            }
        }
    }

    private fun getFlushMode(): Int {
        return if (isEnableFlush) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
    }

    private fun unlockFocus() {
        synchronized(isInFocusing) {
            isInFocusing = false
            try {
                previewRequestBuilder?.let { builder ->
                    captureSession?.let { session ->
                        builder.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_CANCEL)
                        builder.set(FLASH_MODE, FLASH_MODE_OFF)
                        session.capture(builder.build(), captureCallback, backgroundHandler)
                        builder.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        builder.set(CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        builder.set(SCALER_CROP_REGION, camScaler.currentView)
                        captureState = STATE_PREVIEW
                        session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                        if (isFaceCamera && isEnableFlush && !isFlushAvailable) {
                            toggleOffWhiteScreenFlush()
                        }
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e)
            }
        }
    }

    private fun toggleOffWhiteScreenFlush() {
        val window: Window = getActivity()!!.window
        val params: WindowManager.LayoutParams = window.attributes
        params.screenBrightness = lastScreenBrightness
        window.attributes = params
        vFronFlush.visibility = GONE
    }

    private fun initZoom() {
        try {
            camScaler = CamScalier(1f, 1, 1)
            manager.getCameraCharacteristics(cameraId).let {
                val rectInit = it.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val maxZoom = it.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                if (maxZoom != null && rectInit != null) {
                    camScaler = CamScalier(maxZoom, rectInit.width(), rectInit.height())
                }
            }
        } catch (e: Throwable) {
            Timber.e(e)
        }
        showCurrentZoom()
    }

    private fun zoom(zoom: Float) {
        previewRequestBuilder?.let { builder ->
            captureSession?.let { session ->
                synchronized(camScaler) {
                    camScaler.setZoom(zoom)
                    try {
                        builder.set(SCALER_CROP_REGION, camScaler.currentView)
                        session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Timber.e(e)
                    }
                }
            }
        }
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y.toDouble()).toFloat()
    }

    private fun showCurrentZoom() {
        tvZoomLevel.text = "x${String.format("%.1f", camScaler.getCurrentZoom())}"
    }

    private fun toggleFlash() {
        if (isEnableFlush) {
            cameraFlashView.setImageResource(R.drawable.ic_flash_off)
        } else {
            cameraFlashView.setImageResource(R.drawable.ic_flash_on)
        }
        isEnableFlush = !isEnableFlush
    }

    private fun getActivity(): Activity? {
        var context = context
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (event.pointerCount == 2 && isZoomEnabled) {
                val currentFingerSpacing = getFingerSpacing(event)
                if (previousFingerSpacing != 0f) {
                    val delta = camScaler.zoomMax * (currentFingerSpacing - previousFingerSpacing) / width / STEP_ZOOM
                    zoom(camScaler.getCurrentZoom() + delta)
                }
                previousFingerSpacing = currentFingerSpacing
            } else {
                previousFingerSpacing = 0f
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
        showCurrentZoom()
        return true
    }


    companion object {
        private const val STATE_PREVIEW = 0
        private const val STATE_WAITING_LOCK = 1
        private const val STATE_WAITING_PRECAPTURE = 2
        private const val STATE_WAITING_NON_PRECAPTURE = 3
        private const val STATE_PICTURE_TAKEN = 4
        private const val MAX_WAITING_FRAMES = 10
        private const val STEP_ZOOM = 2

        private const val MAX_JPEG_IMAGES = 5
    }
}


