package app.cuentapepas.inference

import android.content.Context
import app.cuentapepas.core.detect.ModelSpec
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer

/** Why the detector runs on the CPU although the GPU is preferred. */
enum class FallbackKind {
    /** The caller asked for the CPU (`preferGpu = false`). */
    GPU_DISABLED_BY_CALLER,

    /** `CompatibilityList.isDelegateSupportedOnThisDevice` is false. */
    GPU_NOT_SUPPORTED,

    /** Creating the GPU delegate, the interpreter with it, or its first inference threw. */
    GPU_INIT_FAILED,
}

/**
 * The model produced NaN, infinity or (at warm-up) only zeros. Typed so the pipeline counts it as a frame error and the
 * UI can show it; [message] is Spanish. Deliberately not a [TfliteDetector.ContractViolation]: on the GPU it triggers
 * the CPU fallback.
 */
class ModelOutputInvalidException(message: String) : IllegalStateException(message)

/** [detail] carries the exception text for [FallbackKind.GPU_INIT_FAILED]; it is diagnostic, not for end users. */
data class DelegateFallback(val kind: FallbackKind, val detail: String? = null)

/**
 * The delegate in use: [name] is `"GPU"` or `"CPU"`; [fallback] is null when the GPU was both preferred and
 * obtained, otherwise it says why the CPU is used.
 */
data class DelegateInfo(val name: String, val fallback: DelegateFallback?)

/**
 * TFLite YOLOX detector with a GPU delegate when the device supports it and a 4-thread CPU (XNNPACK,
 * the TFLite default) fallback.
 *
 * Construction only locates and maps the model file, so a missing model fails immediately with
 * [ModelMissingException]. The interpreter is built by [initialize] (called by [run] on first use) because the
 * GPU delegate is bound to the thread that creates it: **[initialize], [run] and [close] must all be
 * called from the same thread** (the frame-processing thread). [initialize], [run] and [close] enforce this and fail loudly.
 *
 * Buffers are reused between calls, so [run] returns the same [FloatArray] every time; copy it if it must outlive
 * the next call. Not safe for concurrent use.
 *
 * Output checks: [run] throws [ModelOutputInvalidException] on any non-finite output value; the warm-up (fixed
 * non-trivial input) also rejects all-zero output, which on the GPU means falling back to the CPU.
 *
 * Contract checks (all throw, none are silently tolerated): the model input must be float32 `[1, H, W, 3]`
 * with the spec's size, `input.range` must be `[0, 255]`, and the interpreter's output shape must equal
 * `spec.output.shape`.
 */
class TfliteDetector(
    context: Context,
    val spec: ModelSpec,
    /** Resolution variant whose asset folder holds the model (see [ModelAssets.variantDir]). */
    val variantId: String,
    private val preferGpu: Boolean = true,
) : AutoCloseable {

    /** File name of the model asset in use (fp16 preferred, fp32 when the fp16 file is absent). */
    val modelFileName: String

    private val modelBuffer: MappedByteBuffer
    private val inputBytes = spec.inputWidth * spec.inputHeight * 3 * 4
    private val outputSize = spec.output.shape.fold(1) { a, b -> a * b }
    private val outputBytes: ByteBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
    private val outputFloats: FloatBuffer = outputBytes.asFloatBuffer()
    private val outputArray = FloatArray(outputSize)

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var ownerThread: Thread? = null

    @Volatile private var info: DelegateInfo? = null

    @Volatile private var closed = false

    init {
        require(spec.input.dtype == "float32") { "model.json input.dtype must be \"float32\", got \"${spec.input.dtype}\"" }
        require(spec.input.range == listOf(0f, 255f)) {
            "model.json input.range must be [0, 255] (raw BGR values), got ${spec.input.range}"
        }
        val appContext = context.applicationContext
        val fp16 = spec.files.fp16.name
        val fp32 = spec.files.fp32.name
        // fp16 first; fp32 only when the fp16 asset is truly absent. A present-but-unreadable fp16 is an error.
        val dir = ModelAssets.variantDir(appContext, variantId)
        modelFileName = if (ModelAssets.exists(appContext, dir, fp16)) fp16 else fp32
        modelBuffer = ModelAssets.mapModel(appContext, variantId, spec, modelFileName) // ModelMissingException if fp32 is missing too
    }

    /** `"GPU"` or `"CPU"`; null until [initialize] has run. */
    val delegateName: String? get() = info?.name

    /** Delegate and fallback reason; null until [initialize] has run. */
    val delegateInfo: DelegateInfo? get() = info

    /**
     * Builds the interpreter on the calling thread (idempotent). Tries the GPU first when preferred and
     * supported, with a warm-up inference inside the guarded block so a delegate that fails at run time also
     * falls back to the CPU; the reason is recorded in [delegateInfo].
     */
    fun initialize() {
        check(!closed) { "TfliteDetector is closed" }
        if (interpreter != null) {
            checkThread()
            return
        }
        ownerThread = Thread.currentThread()

        val fallback = if (preferGpu) tryGpu() else DelegateFallback(FallbackKind.GPU_DISABLED_BY_CALLER)
        if (interpreter == null) {
            try {
                buildCpu()
            } catch (t: Throwable) {
                releaseNative()
                throw t
            }
        }
        info = DelegateInfo(if (gpuDelegate != null) "GPU" else "CPU", fallback)
    }

    /** Returns null when the GPU interpreter is up, otherwise why it is not (state is cleaned for the CPU attempt). */
    private fun tryGpu(): DelegateFallback? {
        CompatibilityList().use { compat ->
            if (!compat.isDelegateSupportedOnThisDevice) return DelegateFallback(FallbackKind.GPU_NOT_SUPPORTED)
            try {
                val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                gpuDelegate = delegate
                val interp = Interpreter(modelBuffer, Interpreter.Options().addDelegate(delegate))
                interpreter = interp
                verifyContract(interp)
                warmUp(interp)
                return null
            } catch (e: ContractViolation) {
                releaseNative()
                throw e // a wrong model is wrong on the CPU too; do not mask it as a GPU problem
            } catch (e: Exception) {
                releaseNative()
                return DelegateFallback(FallbackKind.GPU_INIT_FAILED, "${e::class.java.simpleName}: ${e.message}")
            } catch (e: LinkageError) { // missing native library or class in the GPU artifact
                releaseNative()
                return DelegateFallback(FallbackKind.GPU_INIT_FAILED, "${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    private fun buildCpu() {
        // XNNPACK is enabled by default in TFLite 2.16; only the thread count needs setting.
        val interp = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(CPU_THREADS))
        interpreter = interp
        verifyContract(interp)
        warmUp(interp)
    }

    /** Frees whatever a failed GPU attempt created, so the CPU attempt starts from nothing. */
    private fun releaseNative() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    private fun verifyContract(interp: Interpreter) {
        val inTensor = interp.getInputTensor(0)
        val expectedIn = intArrayOf(1, spec.inputHeight, spec.inputWidth, 3)
        if (inTensor.dataType() != DataType.FLOAT32 || !inTensor.shape().contentEquals(expectedIn)) {
            throw ContractViolation(
                "Model input is ${inTensor.dataType()} ${inTensor.shape().contentToString()}, " +
                    "model.json declares FLOAT32 ${expectedIn.contentToString()}",
            )
        }
        val outTensor = interp.getOutputTensor(0)
        val expectedOut = spec.output.shape.toIntArray()
        if (!outTensor.shape().contentEquals(expectedOut)) {
            throw ContractViolation(
                "Model output shape ${outTensor.shape().contentToString()} does not match model.json " +
                    "output.shape ${expectedOut.contentToString()}",
            )
        }
        if (outTensor.dataType() != DataType.FLOAT32) {
            throw ContractViolation("Model output is ${outTensor.dataType()}, expected FLOAT32")
        }
    }

    /**
     * Runs a fixed, non-trivial input (a deterministic pattern covering the 0..255 range, not zeros) and checks the
     * output without comparing it to another delegate: every value must be finite and not all of them zero. A GPU
     * delegate that builds fine but returns NaN/zeros would otherwise count nothing, silently.
     * @throws ModelOutputInvalidException when the output is unusable.
     */
    private fun warmUp(interp: Interpreter) {
        val pattern = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())
        val floats = pattern.asFloatBuffer()
        for (i in 0 until floats.capacity()) floats.put(((i * 37 + (i / 7) * 101) % 256).toFloat())
        outputBytes.clear()
        interp.run(pattern, outputBytes)
        outputFloats.rewind()
        outputFloats.get(outputArray)
        val bad = firstNonFinite(outputArray)
        if (bad >= 0) {
            throw ModelOutputInvalidException(
                "El modelo devolvió un valor no válido (${outputArray[bad]}) en la posición $bad durante el calentamiento",
            )
        }
        if (outputArray.all { it == 0f }) {
            throw ModelOutputInvalidException("El modelo devolvió solo ceros durante el calentamiento con una entrada no trivial")
        }
    }

    /**
     * Runs one inference.
     *
     * @param input direct, native-order buffer of exactly `inputWidth * inputHeight * 3` float32 values
     *   (NHWC, BGR), read from its start; its own position and limit are not modified.
     * @return the raw output, `spec.output.shape` flattened row-major, in an array reused by the next call.
     */
    fun run(input: ByteBuffer): FloatArray {
        check(!closed) { "TfliteDetector is closed" }
        if (interpreter == null) initialize() else checkThread()
        require(input.isDirect && input.order() == ByteOrder.nativeOrder()) {
            "input must be a direct buffer in native byte order"
        }
        require(input.capacity() == inputBytes) { "input has ${input.capacity()} bytes, expected $inputBytes" }
        val interp = checkNotNull(interpreter)
        val view = input.duplicate().order(ByteOrder.nativeOrder())
        view.clear()
        outputBytes.clear()
        interp.run(view, outputBytes)
        outputFloats.rewind()
        outputFloats.get(outputArray)
        val bad = firstNonFinite(outputArray)
        if (bad >= 0) {
            throw ModelOutputInvalidException(
                "La salida del modelo contiene un valor no válido (${outputArray[bad]}) en la posición $bad",
            )
        }
        return outputArray
    }

    private fun firstNonFinite(values: FloatArray): Int {
        for (i in values.indices) if (!values[i].isFinite()) return i
        return -1
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) {
            "TfliteDetector must be used from the thread that initialised it (${ownerThread?.name}); " +
                "called from ${Thread.currentThread().name}"
        }
    }

    /**
     * Releases the interpreter and the GPU delegate. Must be called from the thread that ran [initialize] (the GPU
     * delegate is thread-bound), enforced like [run]: another thread gets an [IllegalStateException] and nothing is
     * released. Closing an already closed detector, or one that was never initialised, is a no-op from any thread.
     */
    override fun close() {
        if (closed) return
        if (ownerThread != null) checkThread()
        closed = true
        releaseNative()
    }

    /**
     * The model on disk does not match `model.json`. Extends [IllegalStateException] so callers get the
     * `IllegalStateException` the contract asks for, while the GPU path can tell it apart from a delegate failure.
     */
    class ContractViolation(message: String) : IllegalStateException(message)

    private companion object {
        const val CPU_THREADS = 4
    }
}
