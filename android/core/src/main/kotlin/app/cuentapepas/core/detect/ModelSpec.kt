package app.cuentapepas.core.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Contract with the model, read from `assets/model/model.json` (written by Plan A, Task A6). */
@Serializable
data class ModelSpec(
    @SerialName("model_id") val modelId: String,
    /** Id of the resolution variant this file describes (`variants.json`); absent in the legacy flat layout. */
    val variant: String? = null,
    @SerialName("taxonomy_schema_version") val taxonomySchemaVersion: Int,
    val classes: List<String>,
    val input: Input,
    val output: Output,
    val postprocess: Postprocess,
    val files: Files,
    @SerialName("training_run") val trainingRun: String? = null,
    /** Training metrics; a null value (e.g. NaN written as null by the exporter) must not make the model unloadable. */
    val metrics: Map<String, Double?> = emptyMap(),
    @SerialName("license_attribution") val licenseAttribution: String? = null,
    /** Optional export parity report (free-form: text, list or object), shown as-is in the About screen. */
    val parity: JsonElement? = null,
    /** Optional known limitations of the model (free-form), shown as-is in the About screen. */
    val limitations: JsonElement? = null,
) {
    @Serializable
    data class Input(
        val name: String,
        val width: Int,
        val height: Int,
        val layout: String,
        val dtype: String,
        @SerialName("channel_order") val channelOrder: String,
        val range: List<Float>,
        val letterbox: String,
        @SerialName("pad_value") val padValue: Int,
    )

    @Serializable
    data class Output(
        val shape: List<Int>,
        val layout: List<String>,
        val coords: String,
        val activations: String,
        /** True when the TFLite output is `[1, 5 + numClasses, numAnchors]` instead of `[1, numAnchors, 5 + numClasses]`. */
        val transposed: Boolean = false,
    )

    @Serializable
    data class Postprocess(
        val score: String,
        @SerialName("default_score_threshold") val defaultScoreThreshold: Float,
        val nms: String,
        @SerialName("default_nms_iou") val defaultNmsIou: Float,
        @SerialName("max_detections") val maxDetections: Int,
    )

    @Serializable
    data class Files(val fp16: ModelFile, val fp32: ModelFile)

    @Serializable
    data class ModelFile(val name: String, val sha256: String)

    init {
        require(input.layout == "NHWC") { "model.json: input.layout must be \"NHWC\", got \"${input.layout}\"" }
        require(input.channelOrder == "BGR") { "model.json: input.channel_order must be \"BGR\", got \"${input.channelOrder}\"" }
        require(input.letterbox == "top_left") { "model.json: input.letterbox must be \"top_left\", got \"${input.letterbox}\"" }
        require(output.coords == "input_pixels") { "model.json: output.coords must be \"input_pixels\", got \"${output.coords}\"" }
        require(output.activations == "sigmoid_applied") {
            "model.json: output.activations must be \"sigmoid_applied\", got \"${output.activations}\""
        }
        require(classes.isNotEmpty()) { "model.json: classes is empty" }
        require(input.width > 0 && input.height > 0) { "model.json: input size must be positive: ${input.width}x${input.height}" }
        require(output.shape.size == 3) { "model.json: output.shape must have 3 dims, got ${output.shape}" }
        require(output.shape[0] == 1) { "model.json: output.shape[0] (batch) must be 1, got ${output.shape[0]}" }
        val channels = if (output.transposed) output.shape[1] else output.shape[2]
        require(channels == 5 + classes.size) {
            "model.json: output has $channels channels but 5 + ${classes.size} classes were declared (shape=${output.shape}, transposed=${output.transposed})"
        }
    }

    val inputWidth: Int get() = input.width
    val inputHeight: Int get() = input.height
    val transposed: Boolean get() = output.transposed
    val numClasses: Int get() = classes.size

    /** Number of anchors (candidate boxes) per frame: dim 1, or dim 2 when [transposed]. */
    val numAnchors: Int get() = if (output.transposed) output.shape[2] else output.shape[1]

    val defaultScoreThreshold: Float get() = postprocess.defaultScoreThreshold
    val defaultNmsIou: Float get() = postprocess.defaultNmsIou
    val maxDetections: Int get() = postprocess.maxDetections

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun parse(json: String): ModelSpec = lenientJson.decodeFromString(serializer(), json)
    }
}
