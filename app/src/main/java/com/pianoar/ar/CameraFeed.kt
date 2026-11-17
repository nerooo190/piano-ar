package com.pianoar.ar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 preview streamed into one or more TextureViews at the same time
 * (phone screen and XREAL glasses).
 */
class CameraFeed(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val views = LinkedHashMap<TextureView, Surface?>()

    var previewSize = Size(1920, 1080)
        private set
    var useFront = false
    var onError: ((String) -> Unit)? = null
    var onPreviewSizeChanged: (() -> Unit)? = null
    val isOpen get() = device != null

    fun attach(view: TextureView) {
        if (views.containsKey(view)) return
        views[view] = null
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = restartSession()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                views.remove(view)?.release()
                restartSession()
                return true
            }
        }
        if (view.isAvailable) restartSession()
    }

    fun detach(view: TextureView) {
        views.remove(view)?.release()
        view.surfaceTextureListener = null
        restartSession()
    }

    @SuppressLint("MissingPermission")
    fun open() {
        if (device != null) return
        val id = pickCamera() ?: return onError?.invoke("Keine passende Kamera gefunden") ?: Unit
        val t = HandlerThread("PianoAR-Camera").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { main.post { device = d; restartSession() } }
                override fun onDisconnected(d: CameraDevice) { d.close(); main.post { device = null } }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    main.post { device = null; onError?.invoke("Kamerafehler $error") }
                }
            }, handler)
        } catch (e: Exception) {
            onError?.invoke("Kamera nicht verfügbar: ${e.message}")
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun reopen() { close(); open() }

    private fun pickCamera(): String? {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.firstOrNull() ?: return null
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        // Prefer 16:9 up to 1920x1080, as close to 1080p as possible.
        val best = sizes.filter { it.width <= 1920 && it.height <= 1080 && abs(it.width * 9 - it.height * 16) < it.width / 10 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
        if (best != null && best != previewSize) {
            previewSize = best
            onPreviewSizeChanged?.invoke()
        }
        return id
    }

    private fun restartSession() {
        val dev = device ?: return
        val h = handler ?: return
        runCatching { session?.close() }
        session = null
        val targets = ArrayList<Surface>()
        for ((view, old) in views.entries.toList()) {
            val st = view.surfaceTexture ?: continue
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val s = old?.takeIf { it.isValid } ?: Surface(st).also { views[view] = it }
            targets += s
        }
        if (targets.isEmpty()) return
        val executor = Executor { h.post(it) }
        try {
            dev.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        try {
                            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                targets.forEach(::addTarget)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            s.setRepeatingRequest(req.build(), null, h)
                            main.post { session = s }
                        } catch (e: Exception) {
                            Log.e("PianoAR", "Preview failed", e)
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        main.post { onError?.invoke("Kamera-Stream konnte nicht gestartet werden") }
                    }
                }))
        } catch (e: Exception) {
            Log.e("PianoAR", "Session failed", e)
            onError?.invoke("Kamera-Stream fehlgeschlagen: ${e.message}")
        }
    }
}
