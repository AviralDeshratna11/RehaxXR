package com.creatofit.motion.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.creatofit.motion.core.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

data class TrackingSnapshot(val pose: PoseFrame, val body: BodyState, val analysis: MuscleAnalysis, val mirrored: Boolean)

/** One analysis pipeline: landmark filter -> body solver -> inverse dynamics -> muscle engine. */
class MotionPipeline {
    private val filter = PoseFilter()
    private val solver = BodySolver()
    private val biomechanics = Biomechanics()
    private val engine = MuscleEngine()
    fun reset() { filter.reset(); solver.reset(); biomechanics.reset(); engine.reset() }
    fun process(raw: PoseFrame, up: V3, mirrored: Boolean): TrackingSnapshot? {
        val frame = filter.apply(raw) ?: return null
        val body = solver.solve(frame, up) ?: return null
        return TrackingSnapshot(frame, body, engine.update(body, biomechanics.analyze(body)), mirrored)
    }
}

class TrackingController(private val context: Context) {
    val lostTimeoutMs = 1000L
    val uncertaintyMs = 350L
    val latest = AtomicReference<TrackingSnapshot?>(null)
    val metrics = Metrics()
    val capture = MotionCapture()

    // ---- Demo source ------------------------------------------------------------------------
    @Volatile var demo = false
        set(value) { if (field != value) { field = value; demoPipeline.reset(); demoSnapshot = null } }
    private val demoPipeline = MotionPipeline()
    private var demoSnapshot: TrackingSnapshot? = null
    private var demoStart = 0L
    @Synchronized fun displaySnapshot(now: Long): TrackingSnapshot? {
        if (!demo) return latest.get()
        if (demoStart == 0L) demoStart = now
        val last = demoSnapshot
        if (last == null || now - last.pose.capturedMs >= 33) {
            val out = PoseSynth.demo(now - demoStart)
            val frame = out.frame.copy(capturedMs = now, inferredMs = now)
            demoPipeline.process(frame, out.upCamera, mirrored = false)?.let { demoSnapshot = it }
        }
        return demoSnapshot
    }

    // ---- Gravity ------------------------------------------------------------------------------
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var deviceUp: FloatArray? = null
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            val prev = deviceUp
            deviceUp = if (prev == null || event.sensor.type == Sensor.TYPE_GRAVITY) floatArrayOf(v[0], v[1], v[2])
                else floatArrayOf(prev[0] + (v[0] - prev[0]) * .1f, prev[1] + (v[1] - prev[1]) * .1f, prev[2] + (v[2] - prev[2]) * .1f)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    @Volatile var gravityStatus = "Gravity sensor not started"; private set

    /** Gravity "up" in analysis-image camera space (x right, y up, z toward the camera). */
    private fun upCamera(rotation: Int, front: Boolean): V3 {
        val g = deviceUp ?: return V3.UP
        val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (n < 3f) return V3.UP
        val (dx, dy) = when (rotation) {
            android.view.Surface.ROTATION_90 -> -g[1] to g[0]
            android.view.Surface.ROTATION_180 -> -g[0] to -g[1]
            android.view.Surface.ROTATION_270 -> g[1] to -g[0]
            else -> g[0] to g[1]
        }
        val c = if (front) V3(-dx, dy, -g[2]) else V3(dx, dy, g[2])
        return c.unit()
    }

    // ---- Status ---------------------------------------------------------------------------------
    @Volatile var message = "Ready"; private set
    @Volatile var delegate = "Not started"; private set
    @Volatile var modelSummary = PoseModel.FULL.summary; private set
    @Volatile var timestampPolicy = "Awaiting camera"; private set
    @Volatile var cameraResolution = "—"; private set
    @Volatile private var session: Session? = null
    private var provider: ProcessCameraProvider? = null
    private var generation = 0

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun start(owner: LifecycleOwner, preview: PreviewView, front: Boolean, useGpu: Boolean, model: PoseModel = PoseModel.FULL) {
        stop(); val token = generation
        message = "Opening camera…"
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gravity != null) {
            sensors.registerListener(gravityListener, gravity, SensorManager.SENSOR_DELAY_GAME)
            gravityStatus = if (gravity.type == Sensor.TYPE_GRAVITY) "Gravity sensor" else "Accelerometer (low-pass)"
        } else gravityStatus = "No gravity sensor: assuming an upright phone"
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != generation) return@addListener
            try {
                val p = future.get(); provider = p
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!p.hasCamera(selector)) { message = "This camera is unavailable. Try switching cameras."; return@addListener }
                val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val liveBuilder = Preview.Builder().setTargetRotation(rotation).setTargetFrameRate(Range(30, 30)).setResolutionSelector(resolution)
                Camera2Interop.Extender(liveBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { metrics.camera(SystemClock.uptimeMillis()) }
                })
                val live = liveBuilder.build()
                live.setSurfaceProvider(preview.surfaceProvider)
                val analysis = ImageAnalysis.Builder().setTargetRotation(rotation)
                    .setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val s = Session(model, front, rotation); session = s
                val camera = p.bindToLifecycle(owner, selector, live, analysis)
                val source = Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                s.realtime = source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                timestampPolicy = if (s.realtime) "Sensor REALTIME → uptime" else "Unknown sensor clock: first-frame offset estimate"
                s.executor.execute { s.setup(useGpu) }
                analysis.setAnalyzer(s.executor) { image -> s.analyze(image) }
            } catch (e: Exception) { message = "Camera unavailable: ${e.message}"; session?.close(); session = null }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        capture.stop()
        generation++
        sensors.unregisterListener(gravityListener)
        provider?.unbindAll()
        session?.close(); session = null
        latest.set(null); message = "Paused"
    }

    private inner class Session(private val model: PoseModel, private val front: Boolean, private val rotation: Int) {
        val executor = Executors.newSingleThreadExecutor()
        @Volatile var active = true
        var realtime = false
        private var task: PoseLandmarker? = null
        private var moveNet: MoveNetEstimator? = null
        private var gpu = false
        private var offset: Long? = null
        private var lastTimestamp = -1L
        private val ids = AtomicLong()
        private val pipeline = MotionPipeline()
        private val pending = AtomicReference<Input?>(null)

        fun setup(requestGpu: Boolean) {
            if (!active) return
            try {
                if (!model.hasWorldLandmarks) {
                    moveNet = MoveNetEstimator(context, requestGpu, model)
                    gpu = requestGpu; delegate = if (requestGpu) "GPU · MoveNet" else "CPU · 4 threads · MoveNet"
                    modelSummary = model.summary
                    message = "Step back until your whole body is visible"
                    return
                }
                gpu = requestGpu
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.asset)
                        .setDelegate(if (requestGpu) Delegate.GPU else Delegate.CPU).build())
                    .setRunningMode(RunningMode.LIVE_STREAM).setNumPoses(1)
                    .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e ->
                        message = "Inference error: ${e.message}"
                        pending.getAndSet(null)?.release()
                        if (active && gpu) executor.execute { task?.close(); task = null; setup(false) }
                    }.build()
                task = PoseLandmarker.createFromOptions(context, options)
                delegate = if (requestGpu) "GPU" else "CPU"
                modelSummary = model.summary
                message = "Step back until your whole body is visible"
            } catch (e: Exception) {
                if (requestGpu) setup(false) else message = "Pose model unavailable: ${e.message}"
            }
        }

        fun analyze(image: ImageProxy) {
            val now = SystemClock.uptimeMillis()
            metrics.analyzed(now)
            try {
                if (!active || task == null && moveNet == null || pending.get() != null) { metrics.drop(); return }
                val sensorMs = image.imageInfo.timestamp / 1_000_000
                if (offset == null) offset = now - sensorMs
                val captured = if (realtime) sensorMs - (SystemClock.elapsedRealtime() - now) else sensorMs + offset!!
                // MediaPipe requires strictly increasing timestamps, including millisecond rounding collisions.
                val stamp = maxOf(captured, lastTimestamp + 1)
                lastTimestamp = stamp
                val bitmap = image.toBitmap()
                val degrees = image.imageInfo.rotationDegrees
                val oriented = if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, false)
                if (oriented !== bitmap) bitmap.recycle()
                moveNet?.let { estimator ->
                    val submitted = SystemClock.uptimeMillis()
                    try {
                        cameraResolution = "${oriented.width} × ${oriented.height} → ${model.label} ${model.inputSize} tracked crop"
                        val pose = estimator.detect(oriented, ids.incrementAndGet(), stamp)
                        metrics.pose(SystemClock.uptimeMillis(), submitted)
                        if (pose != null) publish(pose) else message = "Show your shoulders and limbs clearly"
                    } catch (error: Exception) {
                        if (gpu) { moveNet?.close(); moveNet = null; setup(false) }
                        else message = "MoveNet inference failed: ${error.message}"
                    } finally { oriented.recycle() }
                    return
                }
                val mp = BitmapImageBuilder(oriented).build()
                val input = Input(ids.incrementAndGet(), stamp, SystemClock.uptimeMillis(), oriented, mp)
                cameraResolution = "${oriented.width} × ${oriented.height}"
                pending.set(input)
                try { task!!.detectAsync(mp, stamp) } catch (e: Exception) { pending.getAndSet(null)?.release(); message = "Inference failed: ${e.message}" }
            } catch (e: Exception) { message = "Frame conversion failed: ${e.message}" }
            finally { image.close() }
        }

        private fun onResult(result: PoseLandmarkerResult) {
            val input = pending.get() ?: return
            if (result.timestampMs() != input.capturedMs) return
            try {
                if (!active) return
                val now = SystemClock.uptimeMillis(); metrics.pose(now, input.submittedMs)
                if (result.landmarks().isEmpty() || result.worldLandmarks().isEmpty()) {
                    message = "No person detected — step into view"; return
                }
                val image = result.landmarks()[0].map { Landmark(V3(it.x(), it.y(), it.z()), minOf(it.visibility().orElse(1f), it.presence().orElse(1f))) }
                val world = result.worldLandmarks()[0].mapIndexed { i, it -> Landmark(V3(it.x(), it.y(), it.z()), minOf(it.visibility().orElse(image[i].confidence), image[i].confidence)) }
                publish(PoseFrame(input.id, input.capturedMs, now, image, world, input.bitmap.width, input.bitmap.height))
            } finally { if (pending.compareAndSet(input, null)) input.release() }
        }

        private fun publish(raw: PoseFrame) {
            if (!active) return
            val snapshot = pipeline.process(raw, upCamera(rotation, front), front) ?: return
            capture.add(snapshot.pose)
            if (active) latest.set(snapshot)
            val b = snapshot.body
            message = when {
                b.isTracked(Chain.LEG_L) && b.isTracked(Chain.LEG_R) && b.isTracked(Chain.TORSO) -> "Tracking full body"
                b.isTracked(Chain.TORSO) -> "Tracking upper body — step back to include your legs"
                else -> "Move into view"
            }
        }

        fun close() {
            active = false
            executor.execute { task?.close(); task = null; moveNet?.close(); moveNet = null; pending.getAndSet(null)?.release() }
            executor.shutdown()
        }
    }

    private data class Input(val id: Long, val capturedMs: Long, val submittedMs: Long, val bitmap: Bitmap, val image: MPImage) {
        fun release() { image.close(); bitmap.recycle() }
    }
}
