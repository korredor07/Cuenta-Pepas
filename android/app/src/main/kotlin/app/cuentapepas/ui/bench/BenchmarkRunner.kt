package app.cuentapepas.ui.bench

import android.content.Context
import app.cuentapepas.ui.common.describeError
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import app.cuentapepas.core.detect.InputPacker
import app.cuentapepas.core.detect.Letterbox
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.YoloxPostprocessor
import app.cuentapepas.core.perf.BenchmarkSummary
import app.cuentapepas.core.perf.LatencyPoint
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.device.DeviceMonitor
import app.cuentapepas.inference.ModelAssets
import app.cuentapepas.inference.TfliteDetector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class BenchSample(
    val t: Double,
    @SerialName("inf_ms_median_last10s") val infMsMedianLast10s: Double?,
    /** `PowerManager.THERMAL_STATUS_*`, or null when the device reports none (never a fake "none"). */
    val thermal: Int?,
    @SerialName("battery_pct") val batteryPct: Int?,
    @SerialName("battery_temp_c") val batteryTempC: Float?,
)

@Serializable
data class BenchSummaryJson(
    @SerialName("duration_s") val durationS: Double,
    val stopped: Boolean,
    val frames: Int,
    @SerialName("latency_ms_median") val latencyMsMedian: Double?,
    @SerialName("latency_ms_p90") val latencyMsP90: Double?,
    @SerialName("fps_median") val fpsMedian: Double?,
    @SerialName("trend_window_s") val trendWindowS: Double?,
    @SerialName("first_window_median_ms") val firstWindowMedianMs: Double?,
    @SerialName("last_window_median_ms") val lastWindowMedianMs: Double?,
    @SerialName("battery_pct_per_hour") val batteryPctPerHour: Double?,
    @SerialName("max_thermal") val maxThermal: Int?,
    @SerialName("images_used") val imagesUsed: Int,
    @SerialName("images_failed") val imagesFailed: Int,
    /** 10 s sample windows that produced no latency point (no frame completed in them). */
    @SerialName("dropped_samples") val droppedSamples: Int,
    val delegate: String,
    @SerialName("fallback_reason") val fallbackReason: String?,
    @SerialName("model_id") val modelId: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("soc_model") val socModel: String?,
    @SerialName("real_images") val realImages: Boolean,
)

@Serializable
data class BenchResult(val summary: BenchSummaryJson, val samples: List<BenchSample>)

/** Live progress shown by the screen. [chart] holds `(t, median ms)` of the 10 s samples. */
data class BenchProgress(
    val elapsedSec: Double,
    val totalSec: Double,
    val chart: List<Pair<Double, Double>>,
    val delegate: String?,
    val realImages: Boolean?,
)

sealed class BenchOutcome {
    data class Done(val result: BenchResult, val file: File) : BenchOutcome()
    data class Failed(val message: String, val detail: String? = null) : BenchOutcome()
}

/**
 * Sustained benchmark. Everything (detector init, inference, postprocess) runs on ONE dedicated thread because the
 * GPU delegate is thread-bound. Callbacks are invoked from that thread. Results are saved in `filesDir/bench/`.
 */
class BenchmarkRunner(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cp-benchmark") }
    private val stopFlag = AtomicBoolean(false)

    fun stop() = stopFlag.set(true)

    fun shutdown() {
        stopFlag.set(true)
        executor.shutdown()
    }

    fun start(durationSec: Int, preferGpu: Boolean, onProgress: (BenchProgress) -> Unit, onFinished: (BenchOutcome) -> Unit) {
        stopFlag.set(false)
        executor.execute {
            val outcome = try {
                run(durationSec, preferGpu, onProgress)
            } catch (t: Throwable) {
                val info = appContext.describeError(t)
                BenchOutcome.Failed(info.message, info.detail)
            }
            onFinished(outcome)
        }
    }

    private fun run(durationSec: Int, preferGpu: Boolean, onProgress: (BenchProgress) -> Unit): BenchOutcome {
        val variants = ModelAssets.loadVariants(appContext)
        val variantId = variants.default
        val spec = ModelAssets.loadSpec(appContext, variants, variantId)
        val inputs = prepareInputs(spec)
        val buffers = inputs.buffers
        val real = inputs.real
        val post = YoloxPostprocessor(spec.numClasses)
        var monitor: DeviceMonitor? = null
        var detector: TfliteDetector? = null
        try {
            monitor = DeviceMonitor(appContext)
            detector = TfliteDetector(appContext, spec, variantId, preferGpu)
            detector.initialize()
            val info = checkNotNull(detector.delegateInfo)
            val total = durationSec.toDouble()
            val stats = PerfStats()
            val points = ArrayList<LatencyPoint>()
            val samples = ArrayList<BenchSample>()
            val chart = ArrayList<Pair<Double, Double>>()
            val startPct = monitor.batteryPct()
            var dropped = 0
            val t0 = SystemClock.elapsedRealtimeNanos()
            var lastEnd = t0
            var nextSample = BenchmarkSummary.SAMPLE_INTERVAL_SEC
            var lastSampleIdx = 0
            var i = 0
            onProgress(BenchProgress(0.0, total, chart.toList(), info.name, real))
            while (!stopFlag.get() && (SystemClock.elapsedRealtimeNanos() - t0) / 1e9 < total) {
                val s = SystemClock.elapsedRealtimeNanos()
                val raw = detector.run(buffers[i++ % buffers.size])
                val e = SystemClock.elapsedRealtimeNanos()
                post.process(raw, spec.numAnchors, spec.transposed, spec.defaultScoreThreshold, spec.defaultNmsIou, spec.maxDetections)
                val end = SystemClock.elapsedRealtimeNanos()
                val ms = (e - s) / 1e6
                stats.addLatencyMs(ms)
                stats.addFrameIntervalMs((end - lastEnd) / 1e6)
                lastEnd = end
                val now = (end - t0) / 1e9
                points.add(LatencyPoint(now, ms))
                if (now >= nextSample) {
                    val med = BenchmarkSummary.medianInWindow(points.subList(lastSampleIdx, points.size), 0.0, Double.MAX_VALUE)
                    lastSampleIdx = points.size
                    samples.add(BenchSample(now, med, noDataToNull(monitor.thermalStatus), monitor.batteryPct(), monitor.batteryTempC()))
                    if (med != null) chart.add(now to med) else dropped++
                    nextSample += BenchmarkSummary.SAMPLE_INTERVAL_SEC
                    onProgress(BenchProgress(now, total, chart.toList(), info.name, real))
                }
            }
            val endElapsed = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
            val stopped = stopFlag.get()
            val endPct = monitor.batteryPct()
            val trend = BenchmarkSummary.trend(points, endElapsed)
            val summary = BenchSummaryJson(
                durationS = endElapsed,
                stopped = stopped,
                frames = stats.latencyCount,
                latencyMsMedian = stats.inferenceMsMedian,
                latencyMsP90 = stats.inferenceMsP90,
                fpsMedian = stats.fpsMedian,
                trendWindowS = trend?.windowSec,
                firstWindowMedianMs = trend?.firstMedianMs,
                lastWindowMedianMs = trend?.lastMedianMs,
                batteryPctPerHour = BenchmarkSummary.batteryPctPerHour(startPct, endPct, endElapsed),
                maxThermal = noDataToNull(monitor.maxThermalSeen),
                imagesUsed = inputs.used,
                imagesFailed = inputs.failed,
                droppedSamples = dropped,
                delegate = info.name,
                fallbackReason = info.fallback?.let { f -> f.kind.name + (f.detail?.let { ": $it" } ?: "") },
                modelId = spec.modelId,
                deviceModel = Build.MODEL,
                socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
                realImages = real,
            )
            val result = BenchResult(summary, samples)
            val dir = File(appContext.filesDir, "bench").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "$stamp.json")
            // Atomic: a crash never leaves a half-written result that looks complete.
            val tmpFile = File(dir, "$stamp.json.tmp")
            tmpFile.writeText(JSON.encodeToString(result))
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return BenchOutcome.Done(result, file)
        } finally {
            detector?.close()
            monitor?.close()
        }
    }

    private class Inputs(val buffers: List<ByteBuffer>, val real: Boolean, val used: Int, val failed: Int)

    /**
     * Decodes each bench image once and packs it (top-left letterbox, BGR); synthetic noise when none could be used.
     * Undecodable images are counted in `failed`, never silently dropped.
     */
    private fun prepareInputs(spec: ModelSpec): Inputs {
        val names = appContext.assets.list("bench")
            ?.filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }?.sorted().orEmpty()
        val out = ArrayList<ByteBuffer>()
        var failed = 0
        for (n in names) {
            try {
                val bmp = appContext.assets.open("bench/$n").use { BitmapFactory.decodeStream(it) }
                if (bmp == null) {
                    failed++
                    continue
                }
                val lb = Letterbox.fit(bmp.width, bmp.height, spec.inputWidth, spec.inputHeight)
                val scaled = Bitmap.createScaledBitmap(bmp, lb.resizedWidth, lb.resizedHeight, true)
                val px = IntArray(lb.resizedWidth * lb.resizedHeight)
                scaled.getPixels(px, 0, lb.resizedWidth, 0, 0, lb.resizedWidth, lb.resizedHeight)
                out.add(pack(spec, px, lb.resizedWidth, lb.resizedHeight))
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
            } catch (e: java.io.IOException) {
                failed++
            } catch (e: RuntimeException) {
                failed++
            }
        }
        if (out.isNotEmpty()) return Inputs(out, true, out.size, failed)
        val lb = Letterbox.fit(1280, 720, spec.inputWidth, spec.inputHeight)
        val rnd = Random(42)
        val px = IntArray(lb.resizedWidth * lb.resizedHeight) { rnd.nextInt() or (0xFF shl 24) }
        return Inputs(listOf(pack(spec, px, lb.resizedWidth, lb.resizedHeight)), false, 0, failed)
    }

    private fun pack(spec: ModelSpec, px: IntArray, w: Int, h: Int): ByteBuffer {
        val floats = FloatArray(InputPacker.outputSize(spec.inputWidth, spec.inputHeight))
        InputPacker.pack(px, w, h, spec.inputWidth, spec.inputHeight, floats, spec.input.padValue.toFloat())
        val buf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        return buf
    }

    private companion object {
        fun noDataToNull(v: Int): Int? = v.takeIf { it != DeviceMonitor.NO_DATA }

        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}
