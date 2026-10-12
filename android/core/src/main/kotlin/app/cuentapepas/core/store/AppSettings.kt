package app.cuentapepas.core.store

import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.ModelVariant
import app.cuentapepas.core.detect.ModelVariants
import app.cuentapepas.core.quality.QualityThresholds
import app.cuentapepas.core.track.TrackerConfig

/** User-tunable parameters. Everything the pipeline reads at start comes from here, and [snapshot] goes into each session. */
data class AppSettings(
    val quality: QualityThresholds = QualityThresholds(),
    val tracker: TrackerConfig = TrackerConfig(),
    val minHits: Int = 3,
    val minMeanScore: Float = 0.40f,
    val scoreThreshold: Float = 0.30f,
    val nmsIou: Float = 0.45f,
    val gpsMaxAccuracyM: Float = 10f,
    /** Id of the detector input resolution (a `ModelVariant.id`); part of every session snapshot. */
    val detectionVariant: String = ModelVariants.LEGACY_ID,
) {
    init {
        require(minHits >= 1) { "Los cuadros mínimos para contar deben ser al menos 1 (valor: $minHits)" }
        require(minMeanScore.isFinite() && minMeanScore in 0f..1f) { "La confianza media mínima debe estar entre 0 y 1 (valor: $minMeanScore)" }
        require(scoreThreshold.isFinite() && scoreThreshold in 0f..1f) { "El umbral de confianza debe estar entre 0 y 1 (valor: $scoreThreshold)" }
        require(nmsIou.isFinite() && nmsIou in 0f..1f) { "El umbral IoU debe estar entre 0 y 1 (valor: $nmsIou)" }
        require(ModelVariant.ID_REGEX.matches(detectionVariant)) { "La resolución de detección no es válida (valor: «$detectionVariant»)" }
        require(gpsMaxAccuracyM.isFinite() && gpsMaxAccuracyM > 0f) { "La precisión GPS máxima debe ser mayor que 0 (valor: $gpsMaxAccuracyM)" }
    }

    /**
     * Flat string map with stable keys, stored in `SessionMeta.settings` and also the persisted form of the settings.
     * Floats/doubles are written with `toString`, which round-trips exactly.
     */
    fun snapshot(): Map<String, String> = linkedMapOf(
        "quality.minSharpness" to quality.minSharpness.toString(),
        "quality.minBrightness" to quality.minBrightness.toString(),
        "quality.maxBrightness" to quality.maxBrightness.toString(),
        "quality.maxBrightFraction" to quality.maxBrightFraction.toString(),
        "quality.maxFrameWidthsPerSecond" to quality.maxFrameWidthsPerSecond.toString(),
        "quality.onFrames" to quality.onFrames.toString(),
        "quality.offFrames" to quality.offFrames.toString(),
        "tracker.highThresh" to tracker.highThresh.toString(),
        "tracker.lowThresh" to tracker.lowThresh.toString(),
        "tracker.newTrackThresh" to tracker.newTrackThresh.toString(),
        "tracker.matchThresh" to tracker.matchThresh.toString(),
        "tracker.secondMatchThresh" to tracker.secondMatchThresh.toString(),
        "tracker.unconfirmedMatchThresh" to tracker.unconfirmedMatchThresh.toString(),
        "tracker.maxTimeLost" to tracker.maxTimeLost.toString(),
        "tracker.fuseScore" to tracker.fuseScore.toString(),
        "counter.minHits" to minHits.toString(),
        "counter.minMeanScore" to minMeanScore.toString(),
        "detect.scoreThreshold" to scoreThreshold.toString(),
        "detect.nmsIou" to nmsIou.toString(),
        "detection.variant" to detectionVariant,
        "gps.maxAccuracyM" to gpsMaxAccuracyM.toString(),
    )

    companion object {
        /** Defaults of the app; the detection thresholds come from the model contract. */
        fun defaults(spec: ModelSpec, variants: ModelVariants = ModelVariants.legacy(spec)): AppSettings =
            AppSettings(
                scoreThreshold = spec.defaultScoreThreshold,
                nmsIou = spec.defaultNmsIou,
                detectionVariant = variants.default,
            )

        /**
         * Rebuilds settings from a [snapshot] map; keys that are absent keep their value from [base].
         * @throws CorruptDataException (path "settings:<key>") for a value that cannot be parsed or is out of range.
         */
        fun fromMap(map: Map<String, String>, base: AppSettings): AppSettings {
            fun <T> read(key: String, default: T, parse: (String) -> T?): T {
                val raw = map[key] ?: return default
                return parse(raw) ?: throw CorruptDataException("settings:$key", "valor no válido '$raw'")
            }
            fun dbl(k: String, d: Double) = read(k, d) { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
            fun flt(k: String, d: Float) = read(k, d) { it.toFloatOrNull()?.takeIf(Float::isFinite) }
            fun int(k: String, d: Int) = read(k, d) { it.toIntOrNull() }
            val q = base.quality
            val t = base.tracker
            try {
                return AppSettings(
                    quality = QualityThresholds(
                        minSharpness = dbl("quality.minSharpness", q.minSharpness),
                        minBrightness = dbl("quality.minBrightness", q.minBrightness),
                        maxBrightness = dbl("quality.maxBrightness", q.maxBrightness),
                        maxBrightFraction = dbl("quality.maxBrightFraction", q.maxBrightFraction),
                        maxFrameWidthsPerSecond = dbl("quality.maxFrameWidthsPerSecond", q.maxFrameWidthsPerSecond),
                        onFrames = int("quality.onFrames", q.onFrames),
                        offFrames = int("quality.offFrames", q.offFrames),
                    ),
                    tracker = TrackerConfig(
                        highThresh = flt("tracker.highThresh", t.highThresh),
                        lowThresh = flt("tracker.lowThresh", t.lowThresh),
                        newTrackThresh = flt("tracker.newTrackThresh", t.newTrackThresh),
                        matchThresh = dbl("tracker.matchThresh", t.matchThresh),
                        secondMatchThresh = dbl("tracker.secondMatchThresh", t.secondMatchThresh),
                        unconfirmedMatchThresh = dbl("tracker.unconfirmedMatchThresh", t.unconfirmedMatchThresh),
                        maxTimeLost = int("tracker.maxTimeLost", t.maxTimeLost),
                        fuseScore = read("tracker.fuseScore", t.fuseScore) { it.toBooleanStrictOrNull() },
                    ),
                    minHits = int("counter.minHits", base.minHits),
                    minMeanScore = flt("counter.minMeanScore", base.minMeanScore),
                    scoreThreshold = flt("detect.scoreThreshold", base.scoreThreshold),
                    nmsIou = flt("detect.nmsIou", base.nmsIou),
                    gpsMaxAccuracyM = flt("gps.maxAccuracyM", base.gpsMaxAccuracyM),
                    detectionVariant = read("detection.variant", base.detectionVariant) { it.trim().takeIf(ModelVariant.ID_REGEX::matches) },
                )
            } catch (e: IllegalArgumentException) {
                throw CorruptDataException("settings", e.message ?: "valores fuera de rango", e)
            }
        }
    }
}

/** Minimal key/value persistence (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun all(): Map<String, String>

    /** Atomically writes all entries (Android: `commit()`; must throw `IOException` if it fails). */
    fun putAll(entries: Map<String, String>)

    fun clear()
}

/** Loads and saves [AppSettings] through a [KeyValueStore]. Missing keys fall back to [defaults]. */
class SettingsRepository(private val kv: KeyValueStore, private val defaults: AppSettings) {
    /** @throws CorruptDataException when a stored value is unreadable (use [reset] to start over). */
    fun load(): AppSettings = AppSettings.fromMap(kv.all(), defaults)

    fun save(settings: AppSettings) = kv.putAll(settings.snapshot())

    /** Forgets every stored value; [load] then returns the defaults. */
    fun reset() = kv.clear()
}
