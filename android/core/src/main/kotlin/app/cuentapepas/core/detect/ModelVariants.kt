package app.cuentapepas.core.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One detector input resolution shipped in the app (`assets/model/variants.json`). [input] is `[height, width]` of the
 * model input in pixels (the id `r384x640` is height x width), matching the order of the variant id.
 */
@Serializable
data class ModelVariant(
    val id: String,
    @SerialName("label_es") val labelEs: String,
    val input: List<Int>,
) {
    init {
        require(ID_REGEX.matches(id)) { "variants.json: id de variante no válido: «$id»" }
        require(labelEs.isNotBlank()) { "variants.json: la variante $id no tiene nombre (label_es)" }
        require(input.size == 2 && input.all { it > 0 }) {
            "variants.json: input de la variante $id debe ser [alto, ancho] positivos, no $input"
        }
    }

    val inputHeight: Int get() = input[0]
    val inputWidth: Int get() = input[1]

    /**
     * Null when [spec] (the variant's own `model.json`) agrees with this variant, otherwise a Spanish description of
     * the first disagreement. A model whose size differs from what `variants.json` declares must never be run.
     */
    fun mismatch(spec: ModelSpec): String? {
        if (spec.variant != null && spec.variant != id) {
            return "El model.json de la variante $id declara la variante «${spec.variant}»."
        }
        if (spec.inputHeight != inputHeight || spec.inputWidth != inputWidth) {
            return "La variante $id declara una entrada de ${inputHeight}x$inputWidth, pero su model.json dice " +
                "${spec.inputHeight}x${spec.inputWidth}."
        }
        return null
    }

    companion object {
        val ID_REGEX = Regex("[A-Za-z0-9_-]{1,40}")
    }
}

/** The variants the app can run and which one is the default (`assets/model/variants.json`). */
@Serializable
data class ModelVariants(
    val default: String,
    val variants: List<ModelVariant>,
) {
    init {
        require(variants.isNotEmpty()) { "variants.json: la lista de variantes está vacía" }
        val ids = variants.map { it.id }
        require(ids.toSet().size == ids.size) { "variants.json: hay variantes con el mismo id: $ids" }
        require(default in ids) { "variants.json: la variante por defecto «$default» no está en la lista $ids" }
    }

    fun byId(id: String): ModelVariant? = variants.firstOrNull { it.id == id }

    val defaultVariant: ModelVariant get() = variants.first { it.id == default }

    /**
     * The variant to offer when [failedId] cannot be loaded: the one called "Normal" (`r384x640`) when it exists,
     * otherwise the one with the smallest input. Null when [failedId] is the only candidate.
     */
    fun saferThan(failedId: String): ModelVariant? {
        val others = variants.filter { it.id != failedId }
        return others.firstOrNull { it.id == NORMAL_ID } ?: others.minByOrNull { it.inputWidth.toLong() * it.inputHeight }
    }

    companion object {
        /** Id used when `variants.json` is absent and the legacy flat `assets/model/model.json` is used. */
        const val LEGACY_ID = "legacy"

        /** The "Normal (rápido)" variant: the first and smallest resolution. */
        const val NORMAL_ID = "r384x640"

        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** @throws IllegalArgumentException (or a SerializationException) when the JSON is not a valid contract. */
        fun parse(json: String): ModelVariants = lenientJson.decodeFromString(serializer(), json)

        /** The single variant that stands for a legacy flat model of the given [spec]. */
        fun legacy(spec: ModelSpec): ModelVariants =
            ModelVariants(
                default = LEGACY_ID,
                variants = listOf(ModelVariant(LEGACY_ID, "Normal", listOf(spec.inputHeight, spec.inputWidth))),
            )
    }
}
