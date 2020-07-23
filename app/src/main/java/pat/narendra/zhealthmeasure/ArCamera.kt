package pat.narendra.zhealthmeasure

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.widget.*
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.AppBarLayout
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Point.OrientationMode
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.android.synthetic.main.app_bar_main.*
import pat.narendra.zhealthmeasure.helpers.*
import pat.narendra.zhealthmeasure.rendering.BackgroundRenderer
import pat.narendra.zhealthmeasure.rendering.ObjectRenderer
import pat.narendra.zhealthmeasure.rendering.PlaneRenderer
import pat.narendra.zhealthmeasure.rendering.PointCloudRenderer
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

/*
* Copyright 2018 Google Inc. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/



/**
 * This is a simple example that demonstrates how to use the Camera2 API while sharing camera access
 * with ARCore. An on-screen switch can be used to pause and resume ARCore. The app utilizes a
 * trivial sepia camera effect while ARCore is paused, and seamlessly hands camera capture request
 * control over to ARCore when it is running.
 *
 *
 * This app demonstrates:
 *
 *
 *  * Starting in AR or non-AR mode by setting the initial value of `arMode`
 *  * Toggling between non-AR and AR mode using an on screen switch
 *  * Pausing and resuming the app while in AR or non-AR mode
 *  * Requesting CAMERA_PERMISSION when app starts, and each time the app is resumed
 *
 */
class ArCameraActivity() : AppCompatActivity(), GLSurfaceView.Renderer,
    OnImageAvailableListener, OnFrameAvailableListener {

    //Kotlin work around for Java synchronized lock in original java code from sample
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    // Whether the app is currently in AR mode. Initial value determines initial state.
    private var arMode = false

    // Whether the surface texture has been attached to the GL context.
    var isGlAttached = false

    // GL Surface used to draw camera preview image.
    private var surfaceView: GLSurfaceView? = null

    // Text view for displaying on screen status message.
    private var statusTextView: TextView? = null

    // Linear layout that contains preview image and status text.
    private var imageTextLinearLayout: LinearLayout? = null

    // ARCore session that supports camera sharing.
    private var sharedSession: Session? = null

    // Camera capture session. Used by both non-AR and AR modes.
    private var captureSession: CameraCaptureSession? = null

    // Reference to the camera system service.
    private var cameraManager: CameraManager? = null

    // A list of CaptureRequest keys that can cause delays when switching between AR and non-AR modes.
    private var keysThatCanCauseCaptureDelaysWhenModified: List<CaptureRequest.Key<*>>? =
        null

    // Camera device. Used by both non-AR and AR modes.
    private var cameraDevice: CameraDevice? = null

    // Looper handler thread.
    private var backgroundThread: HandlerThread? = null

    // Looper handler.
    private var backgroundHandler: Handler? = null

    // ARCore shared camera instance, obtained from ARCore session that supports sharing.
    private var sharedCamera: SharedCamera? = null

    // Camera ID for the camera used by ARCore.
    private var cameraId: String? = null

    // Ensure GL surface draws only occur when new frames are available.
    private val shouldUpdateSurfaceTexture =
        AtomicBoolean(false)

    // Whether ARCore is currently active.
    private var arcoreActive = false

    // Whether the GL surface has been created.
    private var surfaceCreated = false

    // Camera preview capture request builder
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null

    // Image reader that continuously processes CPU images.
    private var cpuImageReader: ImageReader? = null

    // Total number of CPU images processed.
    private var cpuImagesProcessed = 0

    // Various helper classes, see hello_ar_java sample to learn more.
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private lateinit var tapHelper: TapHelper

    // Renderers, see hello_ar_java sample to learn more.
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    // Anchors created from taps, see hello_ar_java sample to learn more.
    private val anchors = arrayListOf<ColoredAnchor>()

    private val automatorRun =
        AtomicBoolean(false)

    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private var captureSessionChangesPossible = true

    // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
    private val safeToExitApp = ConditionVariable()

    private class ColoredAnchor(a: Anchor, color4f: FloatArray) {
        val anchor: Anchor = a
        val color: FloatArray = color4f


    }

    // Camera device state callback.
    private val cameraDeviceCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(@NonNull cameraDevice: CameraDevice) {
                Log.d(
                    TAG,
                    "Camera device ID " + cameraDevice.id + " opened."
                )
                this@ArCameraActivity.cameraDevice = cameraDevice
                createCameraPreviewSession()
            }

            override fun onClosed(@NonNull cameraDevice: CameraDevice) {
                Log.d(
                    TAG,
                    "Camera device ID " + cameraDevice.id + " closed."
                )
                this@ArCameraActivity.cameraDevice = null
                safeToExitApp.open()
            }

            override fun onDisconnected(@NonNull cameraDevice: CameraDevice) {
                Log.w(
                    TAG,
                    "Camera device ID " + cameraDevice.id + " disconnected."
                )
                cameraDevice.close()
                this@ArCameraActivity.cameraDevice = null
            }

            override fun onError(
                @NonNull cameraDevice: CameraDevice,
                error: Int
            ) {
                Log.e(
                    TAG,
                    "Camera device ID " + cameraDevice.id + " error " + error
                )
                cameraDevice.close()
                this@ArCameraActivity.cameraDevice = null
                // Fatal error. Quit application.
                finish()
            }
        }

    // Repeating camera capture session state callback.
    var cameraSessionStateCallback: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            // Called when the camera capture session is first configured after the app
            // is initialized, and again each time the activity is resumed.
            override fun onConfigured(@NonNull session: CameraCaptureSession) {
                Log.d(
                    TAG,
                    "Camera capture session configured."
                )
                captureSession = session
                if (arMode) {
                    setRepeatingCaptureRequest()
                    // Note, resumeARCore() must be called in onActive(), not here.
                } else {
                    // Calls `setRepeatingCaptureRequest()`.
                    resumeCamera2()
                }
            }

            override fun onSurfacePrepared(
                @NonNull session: CameraCaptureSession, @NonNull surface: Surface
            ) {
                Log.d(
                    TAG,
                    "Camera capture surface prepared."
                )
            }

            override fun onReady(@NonNull session: CameraCaptureSession) {
                Log.d(
                    TAG,
                    "Camera capture session ready."
                )
            }

            override fun onActive(@NonNull session: CameraCaptureSession) {
                Log.d(
                    TAG,
                    "Camera capture session active."
                )
                if (arMode && !arcoreActive) {
                    resumeARCore()
                }
                lock.withLock {
                    captureSessionChangesPossible = true
                    condition.signal()
                }
                updateSnackbarMessage()
            }

            override fun onCaptureQueueEmpty(@NonNull session: CameraCaptureSession) {
                Log.w(
                    TAG,
                    "Camera capture queue empty."
                )
            }

            override fun onClosed(@NonNull session: CameraCaptureSession) {
                Log.d(
                    TAG,
                    "Camera capture session closed."
                )
            }

            override fun onConfigureFailed(@NonNull session: CameraCaptureSession) {
                Log.e(
                    TAG,
                    "Failed to configure camera capture session."
                )
            }
        }

    // Repeating camera capture session capture callback.
    private val cameraCaptureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            @NonNull session: CameraCaptureSession,
            @NonNull request: CaptureRequest,
            @NonNull result: TotalCaptureResult
        ) {
            shouldUpdateSurfaceTexture.set(true)
        }

        override fun onCaptureBufferLost(
            @NonNull session: CameraCaptureSession,
            @NonNull request: CaptureRequest,
            @NonNull target: Surface,
            frameNumber: Long
        ) {
            Log.e(
                TAG,
                "onCaptureBufferLost: $frameNumber"
            )
        }

        override fun onCaptureFailed(
            @NonNull session: CameraCaptureSession,
            @NonNull request: CaptureRequest,
            @NonNull failure: CaptureFailure
        ) {
            Log.e(
                TAG,
                "onCaptureFailed: " + failure.frameNumber + " " + failure.reason
            )
        }

        override fun onCaptureSequenceAborted(
            @NonNull session: CameraCaptureSession, sequenceId: Int
        ) {
            Log.e(
                TAG,
                "onCaptureSequenceAborted: $sequenceId $session"
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_camera)
        val extraBundle: Bundle? = intent.extras
        if (extraBundle != null && 1 == extraBundle.getShort(
                AUTOMATOR_KEY,
                AUTOMATOR_DEFAULT
            ).toInt()
        ) {
            automatorRun.set(true)
        }

        // GL surface view that renders camera preview image.
        surfaceView = findViewById(R.id.glsurfaceview)
        surfaceView?.let { surfaceView ->
            surfaceView.preserveEGLContextOnPause = true
            surfaceView.setEGLContextClientVersion(2)
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            surfaceView.setRenderer(this)
            surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            // Helpers, see hello_ar_java sample to learn more.
            displayRotationHelper = DisplayRotationHelper(this)
            tapHelper = TapHelper(this)
            surfaceView.setOnTouchListener(tapHelper)
            imageTextLinearLayout = findViewById(R.id.image_text_layout)
            statusTextView = findViewById(R.id.text_view)

            // Switch to allow pausing and resuming of ARCore.
            val arcoreSwitch: Switch = findViewById(R.id.arcore_switch)
            // Ensure initial switch position is set based on initial value of `arMode` variable.
            arcoreSwitch.isChecked = arMode
            arcoreSwitch.setOnCheckedChangeListener { view: CompoundButton?, checked: Boolean ->
                Log.i(
                    TAG,
                    "Switching to " + (if (checked) "AR" else "non-AR") + " mode."
                )
                if (checked) {
                    arMode = true
                    resumeARCore()
                } else {
                    arMode = false
                    pauseARCore()
                    resumeCamera2()
                }
                updateSnackbarMessage()
            }
            messageSnackbarHelper.setMaxLines(4)
            updateSnackbarMessage()
            Log.d(TAG, "got to end of oncreate")
        }
    }

    private fun waitUntilCameraCaptureSessionIsActive() {
        lock.withLock {
            while (!captureSessionChangesPossible) {
                try {
                    condition.await()
                } catch (e: InterruptedException) {
                    Log.e(
                        TAG,
                        "Unable to wait for a safe time to make changes to the capture session",
                        e
                    )
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        waitUntilCameraCaptureSessionIsActive()
        startBackgroundThread()
        surfaceView?.onResume()

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (surfaceCreated) {
            openCamera()
        }
        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        shouldUpdateSurfaceTexture.set(false)
        surfaceView?.onPause()
        waitUntilCameraCaptureSessionIsActive()
        displayRotationHelper?.onPause()
        if (arMode) {
            pauseARCore()
        }
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun resumeCamera2() {
        setRepeatingCaptureRequest()
        sharedCamera?.getSurfaceTexture()?.setOnFrameAvailableListener(this)
    }

    private fun resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (sharedSession == null) {
            return
        }
        if (!arcoreActive) {
            try {
                // To avoid flicker when resuming ARCore mode inform the renderer to not suppress rendering
                // of the frames with zero timestamp.
                backgroundRenderer.suppressTimestampZeroRendering(false)
                // Resume ARCore.
                sharedSession?.resume()
                arcoreActive = true
                updateSnackbarMessage()

                // Set capture session callback while in AR mode.
                sharedCamera?.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
            } catch (e: CameraNotAvailableException) {
                Log.e(
                    TAG,
                    "Failed to resume ARCore session",
                    e
                )
                return
            }
        }
    }

    private fun pauseARCore() {
        if (arcoreActive) {
            // Pause ARCore.
            sharedSession?.pause()
            arcoreActive = false
            updateSnackbarMessage()
        }
    }

    private fun updateSnackbarMessage() {
        messageSnackbarHelper.showMessage(
            this,
            if (arcoreActive) "ARCore is active.\nSearch for plane, then tap to place a 3D model." else "ARCore is paused.\nCamera effects enabled."
        )
    }

    // Called when starting non-AR mode or switching to non-AR mode.
    // Also called when app starts in AR mode, or resumes in AR mode.
    private fun setRepeatingCaptureRequest() {
        try {
            setCameraEffects(previewCaptureRequestBuilder)
            previewCaptureRequestBuilder?.build()?.let {
                captureSession?.setRepeatingRequest(
                    it, cameraCaptureCallback, backgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(
                TAG,
                "Failed to set repeating request",
                e
            )
        }
    }

    private fun createCameraPreviewSession() {
        try {
            // Note that isGlAttached will be set to true in AR mode in onDrawFrame().
            sharedSession?.setCameraTextureName(backgroundRenderer.textureId)
            sharedCamera?.surfaceTexture?.setOnFrameAvailableListener(this)

            // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
            previewCaptureRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            // Build surfaces list, starting with ARCore provided surfaces.
            val surfaceList: MutableList<Surface>? =
                sharedCamera?.arCoreSurfaces

            // Add a CPU image reader surface. On devices that don't support CPU image access, the image
            // may arrive significantly later, or not arrive at all.
            cpuImageReader?.surface?.let { surfaceList?.add(it) }

            // Surface list should now contain three surfaces:
            // 0. sharedCamera.getSurfaceTexture()
            // 1. …
            // 2. cpuImageReader.getSurface()

            // Add ARCore surfaces and CPU image surface targets.
            val sList = surfaceList
            if (sList != null) {
                for (surface: Surface in sList) {
                    previewCaptureRequestBuilder?.addTarget(surface)
                }
            }

            // Wrap our callback in a shared camera callback.
            var wrappedCallback: CameraCaptureSession.StateCallback? = null
            sharedCamera?.let {
                wrappedCallback = it.createARSessionStateCallback(
                    cameraSessionStateCallback,
                    backgroundHandler
                )
            }
            // Create camera capture session for camera preview using ARCore wrapped callback.
            if (surfaceList != null && wrappedCallback != null) {
                cameraDevice?.createCaptureSession(
                    surfaceList,
                    wrappedCallback!!,
                    backgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException", e)
        }
    }

    // Start background handler thread, used to run callbacks without blocking UI thread.
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("sharedCameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    // Stop background handler thread.
    private fun stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread?.quitSafely()
            try {
                backgroundThread?.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(
                    TAG,
                    "Interrupted while trying to join background handler thread",
                    e
                )
            }
        }
    }

    // Perform various checks, then open camera device and create CPU image reader.
    private fun openCamera() {
        // Don't open camera if already opened.
        if (cameraDevice != null) {
            return
        }

        // Verify CAMERA_PERMISSION has been granted.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        // Make sure that ARCore is installed, up to date, and supported on this device.

        //todo removed this check temporarily -- uncomment and debug the method called below
/*        if (!isARCoreSupportedAndUpToDate) {
            return
        }*/
        if (sharedSession == null) {
            try {
                // Create ARCore session that supports camera sharing.
                sharedSession = Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: UnavailableException) {
                Log.e(
                    TAG,
                    "Failed to create ARCore session that supports camera sharing",
                    e
                )
                return
            }

            // Enable auto focus mode while ARCore is running.

            sharedSession?.let {
                val config = it.config
                config.focusMode = Config.FocusMode.AUTO
                it.configure(config)
            }

        }

        // Store the ARCore shared camera reference.
        sharedCamera = sharedSession?.sharedCamera

        // Store the ID of the camera used by ARCore.
        cameraId = sharedSession?.cameraConfig?.cameraId

        // Use the currently configured CPU image size.
        sharedSession?.cameraConfig?.imageSize?.let {
            cpuImageReader = ImageReader.newInstance(
                it.width,
                it.height,
                ImageFormat.YUV_420_888,
                2
            )
        }

        cpuImageReader?.setOnImageAvailableListener(this, backgroundHandler)

        // When ARCore is running, make sure it also updates our CPU image surface.
        sharedCamera?.setAppSurfaces(
            cameraId,
            listOf(cpuImageReader?.surface)
        )
        try {

            // Wrap our callback in a shared camera callback.
            var wrappedCallback: CameraDevice.StateCallback? = null
            sharedCamera?.let {
                wrappedCallback = it.createARDeviceStateCallback(
                    cameraDeviceCallback,
                    backgroundHandler
                )
            }

            // Store a reference to the camera system service.
            cameraManager =
                this.getSystemService(Context.CAMERA_SERVICE) as CameraManager?

            // Get the characteristics for the ARCore camera.
            val characteristics =
                cameraManager?.getCameraCharacteristics(cameraId!!)

            // On Android P and later, get list of keys that are difficult to apply per-frame and can
            // result in unexpected delays when modified during the capture session lifetime.
            if (Build.VERSION.SDK_INT >= 28) {
                keysThatCanCauseCaptureDelaysWhenModified =
                    characteristics?.availableSessionKeys
                if (keysThatCanCauseCaptureDelaysWhenModified == null) {
                    // Initialize the list to an empty list if getAvailableSessionKeys() returns null.
                    keysThatCanCauseCaptureDelaysWhenModified =
                        ArrayList()
                }
            }

            // Prevent app crashes due to quick operations on camera open / close by waiting for the
            // capture session's onActive() callback to be triggered.
            captureSessionChangesPossible = false

            // Open the camera device using the ARCore wrapped callback.
            wrappedCallback?.let { cameraManager?.openCamera(cameraId!!, it, backgroundHandler) }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun <T> checkIfKeyCanCauseDelay(key: CaptureRequest.Key<T>): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            // On Android P and later, return true if key is difficult to apply per-frame.
            return keysThatCanCauseCaptureDelaysWhenModified!!.contains(key)
        } else {
            // On earlier Android versions, log a warning since there is no API to determine whether
            // the key is difficult to apply per-frame. Certain keys such as CONTROL_AE_TARGET_FPS_RANGE
            // are known to cause a noticeable delay on certain devices.
            // If avoiding unexpected capture delays when switching between non-AR and AR modes is
            // important, verify the runtime behavior on each pre-Android P device on which the app will
            // be distributed. Note that this device-specific runtime behavior may change when the
            // device's operating system is updated.
            Log.w(
                TAG,
                "Changing "
                        + key
                        + " may cause a noticeable capture delay. Please verify actual runtime behavior on"
                        + " specific pre-Android P devices that this app will be distributed on."
            )
            // Allow the change since we're unable to determine whether it can cause unexpected delays.
            return false
        }
    }

    // If possible, apply effect in non-AR mode, to help visually distinguish between from AR mode.
    private fun setCameraEffects(captureBuilder: CaptureRequest.Builder?) {
        if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
            Log.w(
                TAG,
                "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions."
            )
        } else {
            Log.d(
                TAG,
                "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode."
            )
            captureBuilder?.set(
                CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA
            )
        }
    }

    // Close the camera device.
    private fun closeCamera() {
        if (captureSession != null) {
            captureSession?.close()
            captureSession = null
        }
        if (cameraDevice != null) {
            waitUntilCameraCaptureSessionIsActive()
            safeToExitApp.close()
            cameraDevice?.close()
            safeToExitApp.block()
        }
        if (cpuImageReader != null) {
            cpuImageReader?.close()
            cpuImageReader = null
        }
    }

    // Surface texture on frame available callback, used only in non-AR mode.
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        // Log.d(TAG, "onFrameAvailable()");
    }

    // CPU image reader callback.
    override fun onImageAvailable(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            Log.w(
                TAG,
                "onImageAvailable: Skipping null image."
            )
            return
        }
        image.close()
        cpuImagesProcessed++

        // Reduce the screen update to once every two seconds with 30fps if running as automated test.
        if (!automatorRun.get() || automatorRun.get() && cpuImagesProcessed % 60 == 0) {
            runOnUiThread {
                statusTextView?.text = ("CPU images processed: "
                        + cpuImagesProcessed
                        + "\n\nMode: "
                        + (if (arMode) "AR" else "non-AR")
                        + " \nARCore active: "
                        + arcoreActive
                        + " \nShould update surface texture: "
                        + shouldUpdateSurfaceTexture.get())
            }
        }
    }

    // Android permission request callback.
/*
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>?,
        results: IntArray?
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                getApplicationContext(),
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }
*/

    // Android focus change callback.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // GL surface created callback. Will be called on the GL thread.
    override fun onSurfaceCreated(
        gl: GL10,
        config: EGLConfig
    ) {
        surfaceCreated = true

        // Set GL clear color to black.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the camera preview image texture. Used in non-AR and AR mode.
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(
                this, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
            openCamera()
        } catch (e: IOException) {
            Log.e(
                TAG,
                "Failed to read an asset file",
                e
            )
        }
    }

    // GL surface changed callback. Will be called on the GL thread.
    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper?.onSurfaceChanged(width, height)
        runOnUiThread {
            // Adjust layout based on display orientation.
            imageTextLinearLayout?.orientation =
                if (width > height) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
    }

    // GL draw callback. Will be called each frame on the GL thread.
    override fun onDrawFrame(gl: GL10) {
        // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return
        }

        // Handle display rotations.
        sharedSession?.let { displayRotationHelper?.updateSessionIfNeeded(it) }
        try {
            if (arMode) {
                onDrawFrameARCore()
            } else {
                onDrawFrameCamera2()
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(
                TAG,
                "Exception on the OpenGL thread",
                t
            )
        }
    }

    // Draw frame when in non-AR mode. Called on the GL thread.
    fun onDrawFrameCamera2() {

        sharedCamera?.let { sharedCamera ->
            val texture: SurfaceTexture = sharedCamera.surfaceTexture
            // Ensure the surface is attached to the GL context.
            if (!isGlAttached) {
                texture.attachToGLContext(backgroundRenderer.textureId)
                isGlAttached = true
            }

            // Update the surface.
            texture.updateTexImage()

            // Account for any difference between camera sensor orientation and display orientation.
            val rotationDegrees: Int =
                displayRotationHelper?.getCameraSensorToDisplayRotation(cameraId) ?: 0

            // Determine size of the camera preview image.
            val size: Size = sharedSession?.cameraConfig?.textureSize ?: Size(640, 480)

            // Determine aspect ratio of the output GL surface, accounting for the current display rotation
            // relative to the camera sensor orientation of the device.
            val displayAspectRatio: Float =
                displayRotationHelper?.getCameraSensorRelativeViewportAspectRatio(cameraId) ?: 1f

            // Render camera preview image to the GL surface.
            backgroundRenderer.draw(
                size.width,
                size.height,
                displayAspectRatio,
                rotationDegrees
            )
        }
    }

    // Draw frame when in AR mode. Called on the GL thread.
    @Throws(CameraNotAvailableException::class)
    fun onDrawFrameARCore() {
        if (!arcoreActive) {
            // ARCore not yet active, so nothing to draw yet.
            return
        }

        // Perform ARCore per-frame update.
        sharedSession?.let { session ->
            val frame: Frame = session.update()
            val camera: Camera = frame.getCamera()


            // ARCore attached the surface to GL context using the texture ID we provided
            // in createCameraPreviewSession() via sharedSession.setCameraTextureName(…).
            isGlAttached = true

            // Handle screen tap.
            handleTap(frame, camera)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState())

            // If not tracking, don't draw 3D objects.
            if (camera.getTrackingState() === TrackingState.PAUSED) {
                return
            }

            // Get projection matrix.
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            val colorCorrectionRgba = FloatArray(4)
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0)
            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewmtx, projmtx)
            }

            // If we detected any plane and snackbar is visible, then hide the snackbar.
            if (messageSnackbarHelper.isShowing) {
                for (plane: Plane in session.getAllTrackables(Plane::class.java)) {
                    if (plane.getTrackingState() === TrackingState.TRACKING) {
                        messageSnackbarHelper.hide(this)
                        break
                    }
                }
            }

            // Visualize planes.
            planeRenderer.drawPlanes(
                session.getAllTrackables(Plane::class.java),
                camera.getDisplayOrientedPose(),
                projmtx
            )

            // Visualize anchors created by touch.
            val scaleFactor = 1.0f
            for (coloredAnchor in anchors) {
                if (coloredAnchor.anchor.trackingState !== TrackingState.TRACKING) {
                    continue
                }
                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to sharedSession.update() as ARCore refines its estimate of the world.
                coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0)

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
                virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
            }
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap: MotionEvent = tapHelper.poll()
        if (camera.trackingState === TrackingState.TRACKING) {
            for (hit: HitResult in frame.hitTest(tap)) {
                // Check if any plane was hit, and if it was hit inside the plane polygon
                val trackable: Trackable = hit.getTrackable()
                // Creates an anchor if a plane or an oriented point was hit.
                if ((trackable is Plane
                            && trackable.isPoseInPolygon(hit.hitPose)
                            && PlaneRenderer.calculateDistanceToPlane(
                        hit.hitPose,
                        camera.pose
                    ) > 0)
                    || (trackable is Point
                            && trackable.orientationMode
                            === OrientationMode.ESTIMATED_SURFACE_NORMAL)
                ) {
                    // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size >= 20) {
                        anchors[0].anchor.detach()
                        anchors.removeAt(0)
                    }

                    // Assign a color to the object for rendering based on the trackable type
                    // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
                    // for AR_TRACKABLE_PLANE, it's green color.
                    val objColor: FloatArray
                    if (trackable is Point) {
                        objColor = floatArrayOf(66.0f, 133.0f, 244.0f, 255.0f)
                    } else if (trackable is Plane) {
                        objColor = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
                    } else {
                        objColor = DEFAULT_COLOR
                    }

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    anchors.add(
                        ColoredAnchor(
                            hit.createAnchor(),
                            objColor
                        )
                    )
                    break
                }
            }
        }
    }/*userRequestedInstall=*/// Request ARCore installation or update if needed.

    //todo fix the below code and the reference to it

    // Make sure ARCore is installed and supported on this device.
/*
    private val isARCoreSupportedAndUpToDate: Boolean
        get() {
            // Make sure ARCore is installed and supported on this device.
            val availability: ArCoreApk.Availability =
                ArCoreApk.getInstance().checkAvailability(this)
            when (availability) {
                SUPPORTED_INSTALLED -> {
                }
                SUPPORTED_APK_TOO_OLD, SUPPORTED_NOT_INSTALLED -> try {
                    // Request ARCore installation or update if needed.
                    val installStatus: ArCoreApk.InstallStatus =
                        ArCoreApk.getInstance().requestInstall(this,  */
/*userRequestedInstall=*//*
true)
                    when (installStatus) {
                        INSTALL_REQUESTED -> {
                            Log.e(
                                TAG,
                                "ARCore installation requested."
                            )
                            return false
                        }
                        INSTALLED -> {
                        }
                    }
                } catch (e: UnavailableException) {
                    Log.e(TAG, "ARCore not installed", e)
                    runOnUiThread {
                        Toast.makeText(
                            getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    finish()
                    return false
                }
                UNKNOWN_ERROR, UNKNOWN_CHECKING, UNKNOWN_TIMED_OUT, UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Log.e(
                        TAG,
                        "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned "
                                + availability
                    )
                    runOnUiThread {
                        Toast.makeText(
                            getApplicationContext(),
                            ("ARCore is not supported on this device, "
                                    + "ArCoreApk.checkAvailability() returned "
                                    + availability),
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    return false
                }
                else -> {}
            }
            return true
        }
*/

    companion object {
        private val TAG = ArCameraActivity::class.java.simpleName
        private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

        // Required for test run.
        private val AUTOMATOR_DEFAULT: Short = 0
        private val AUTOMATOR_KEY = "automator"
    }
}