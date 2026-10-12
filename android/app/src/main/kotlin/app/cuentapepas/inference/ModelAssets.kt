package app.cuentapepas.inference

import android.content.Context
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.ModelVariants
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Base of every failure to obtain a usable model; the UI maps the subtype to a Spanish message. */
sealed class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An expected file is not in `assets/model/` (the ML export was never copied there). */
class ModelMissingException(val assetPath: String) :
    ModelException("Falta el archivo del modelo en los recursos de la app: $assetPath (vea assets/model/README.txt)")

/** The file exists but cannot be parsed, or its contract is not one this app supports. */
class ModelInvalidException(message: String, cause: Throwable? = null) : ModelException(message, cause)

/** The file exists but could not be read or mapped (I/O error, or the asset was compressed). */
class ModelLoadException(val assetPath: String, cause: Throwable) :
    ModelException("No se pudo leer el recurso del modelo $assetPath", cause)

/**
 * Access to the model files shipped in `assets/model/`.
 *
 * Layout: `assets/model/variants.json` lists the input resolutions and each variant lives in
 * `assets/model/<id>/{model.json, *.tflite}`. When `variants.json` is absent the legacy flat layout
 * (`assets/model/model.json` + `.tflite`) is a single variant called [ModelVariants.LEGACY_ID].
 */
object ModelAssets {
    const val DIR = "model"
    const val SPEC_FILE = "model.json"
    const val VARIANTS_FILE = "variants.json"

    @Volatile private var legacyLayout: Boolean? = null

    /** True when `variants.json` is absent and the flat `assets/model/model.json` is used. */
    fun isLegacy(context: Context): Boolean =
        legacyLayout ?: !exists(context, DIR, VARIANTS_FILE).also { legacyLayout = !it }

    /** Asset directory of [variantId]: `model` in the legacy layout, otherwise `model/<id>`. */
    fun variantDir(context: Context, variantId: String): String =
        if (isLegacy(context)) DIR else "$DIR/$variantId"

    /**
     * Reads `variants.json` (or, when absent, wraps the legacy flat model.json as one variant).
     * @throws ModelException when a file is missing or invalid.
     */
    fun loadVariants(context: Context): ModelVariants {
        if (isLegacy(context)) return ModelVariants.legacy(readSpec(context, DIR))
        val path = "$DIR/$VARIANTS_FILE"
        val text = readText(context, path)
        return try {
            ModelVariants.parse(text)
        } catch (e: Exception) { // SerializationException, IllegalArgumentException from the init checks
            throw ModelInvalidException(context.getString(R.string.variants_invalid, e.message ?: path), e)
        }
    }

    /**
     * Reads and validates the model.json of [variantId] and checks it agrees with [variants].
     * @throws ModelException when it is missing or invalid, or the variant does not exist.
     */
    fun loadSpec(context: Context, variants: ModelVariants, variantId: String): ModelSpec {
        val variant = variants.byId(variantId)
            ?: throw ModelInvalidException(
                context.getString(R.string.variant_unknown, variantId, variants.variants.joinToString { it.id }),
            )
        val spec = readSpec(context, variantDir(context, variant.id))
        variant.mismatch(spec)?.let { throw ModelInvalidException(it) }
        return spec
    }

    private fun readSpec(context: Context, dir: String): ModelSpec {
        val path = "$dir/$SPEC_FILE"
        val text = readText(context, path)
        return try {
            ModelSpec.parse(text)
        } catch (e: Exception) { // SerializationException, IllegalArgumentException from ModelSpec.init
            throw ModelInvalidException("$path no es un contrato de modelo válido", e)
        }
    }

    private fun readText(context: Context, path: String): String {
        val dir = path.substringBeforeLast('/')
        if (!exists(context, dir, path.substringAfterLast('/'))) throw ModelMissingException(path)
        return try {
            context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            throw ModelLoadException(path, e)
        }
    }

    /** @throws ModelLoadException when the asset directory cannot be listed (an I/O error is not "absent"). */
    fun exists(context: Context, dir: String, fileName: String): Boolean =
        try {
            context.assets.list(dir)?.contains(fileName) == true
        } catch (e: IOException) {
            throw ModelLoadException(dir, e)
        }

    /** The file TfliteDetector uses: fp16 when its asset is present, otherwise fp32. */
    fun preferredFile(context: Context, variantId: String, spec: ModelSpec): ModelSpec.ModelFile =
        if (exists(context, variantDir(context, variantId), spec.files.fp16.name)) spec.files.fp16 else spec.files.fp32

    private val verified = java.util.concurrent.ConcurrentHashMap<String, String>() // "dir/file" -> sha256 already checked

    /**
     * Memory-maps `<variant dir>/<fileName>` and checks it against the sha256 declared in [spec].
     * Requires the asset to be stored uncompressed (`noCompress += "tflite"`).
     */
    fun mapModel(context: Context, variantId: String, spec: ModelSpec, fileName: String): MappedByteBuffer {
        val dir = variantDir(context, variantId)
        val path = "$dir/$fileName"
        if (!exists(context, dir, fileName)) throw ModelMissingException(path)
        val buffer = try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: IOException) {
            throw ModelLoadException(path, e)
        }
        val declared = listOf(spec.files.fp16, spec.files.fp32).firstOrNull { it.name == fileName }
            ?: throw ModelInvalidException(context.getString(R.string.model_file_undeclared, path))
        verifySha256(context, path, buffer, declared.sha256)
        return buffer
    }

    /**
     * Checks the mapped bytes against [expected] (hex, from model.json). A mismatch or a malformed digest is a
     * [ModelInvalidException]. A file already verified in this process is not hashed again.
     */
    private fun verifySha256(context: Context, path: String, buffer: MappedByteBuffer, expected: String) {
        val want = expected.trim().lowercase()
        if (verified[path] == want) return
        if (!Regex("[0-9a-f]{64}").matches(want)) {
            throw ModelInvalidException(context.getString(R.string.model_sha_malformed, path))
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(buffer.duplicate()) // duplicate: the caller's position/limit stay untouched
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) throw ModelInvalidException(context.getString(R.string.model_sha_mismatch, path))
        verified[path] = want
    }
}
