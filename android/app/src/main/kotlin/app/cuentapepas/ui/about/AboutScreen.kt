package app.cuentapepas.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.ErrorInfo
import app.cuentapepas.ui.common.describeError
import app.cuentapepas.inference.ModelAssets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import app.cuentapepas.inference.ModelException
import java.io.IOException

private class AboutData(
    val version: String,
    val spec: ModelSpec?,
    val specError: ErrorInfo?,
    val fileLine: String?,
    val sha: String?,
    val fileError: ErrorInfo?,
    val notice: String?,
    val noticeError: String?,
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val loader = rememberLoader(Unit) {
        var spec: ModelSpec? = null
        var specError: ErrorInfo? = null
        var fileLine: String? = null
        var sha: String? = null
        var fileError: ErrorInfo? = null
        try {
            spec = services.modelSpec
        } catch (e: ModelException) {
            specError = context.describeError(e)
        }
        if (spec != null) {
            try {
                val f = ModelAssets.preferredFile(context, services.variants.default, spec)
                fileLine = context.getString(
                    R.string.about_model_file,
                    f.name,
                    if (f === spec.files.fp16) "fp16" else "fp32",
                )
                sha = f.sha256
            } catch (e: ModelException) {
                fileError = context.describeError(e)
            }
        }
        var notice: String? = null
        var noticeError: String? = null
        try {
            notice = context.assets.open("NOTICE.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            noticeError = e.message ?: e::class.java.simpleName
        }
        AboutData(services.appVersion, spec, specError, fileLine, sha, fileError, notice, noticeError)
    }
    ScreenScaffold(stringResource(R.string.about_title), onBack) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        LoadView(loader) { d ->
            Text(stringResource(R.string.about_version, d.version))
            d.specError?.let { ErrorText(it.message, it.detail) }
            d.spec?.let { spec ->
                Text(stringResource(R.string.about_model_id, spec.modelId))
                d.fileLine?.let { Text(it) }
                d.sha?.let { Text(stringResource(R.string.about_model_file_sha, it), style = MaterialTheme.typography.bodySmall) }
                d.fileError?.let { ErrorText(it.message, it.detail) }
                Text(stringResource(R.string.about_training_run, spec.trainingRun ?: Fmt.NO_DATA))
                Text(stringResource(R.string.about_classes, spec.classes.joinToString(", ")))
                Text(stringResource(R.string.about_metrics_title), style = MaterialTheme.typography.titleMedium)
                if (spec.metrics.isEmpty()) Text(Fmt.NO_DATA)
                for ((k, v) in spec.metrics.toSortedMap()) {
                    Text(stringResource(R.string.about_metric_line, k, Fmt.orNoData(v, 3)))
                }
                spec.parity?.let {
                    Text(stringResource(R.string.about_parity_title), style = MaterialTheme.typography.titleMedium)
                    Text(jsonText(it), style = MaterialTheme.typography.bodySmall)
                }
                spec.limitations?.let {
                    Text(stringResource(R.string.about_model_limits_title), style = MaterialTheme.typography.titleMedium)
                    Text(jsonText(it), style = MaterialTheme.typography.bodySmall)
                }
                spec.licenseAttribution?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            ModelVariantsAbout()
            Text(stringResource(R.string.about_limits_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_limits_text))
            Text(stringResource(R.string.about_licenses_title), style = MaterialTheme.typography.titleMedium)
            d.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            d.noticeError?.let { ErrorText(stringResource(R.string.about_notice_missing, it)) }
        }
    }
}

/** Free-form model.json text as lines: strings as they are, arrays as bullets, objects as "key: value". */
internal fun jsonText(e: JsonElement, indent: String = ""): String = when (e) {
    is JsonNull -> app.cuentapepas.core.format.Fmt.NO_DATA
    is JsonPrimitive -> e.content
    is JsonArray -> e.joinToString("\n") { "$indent• ${jsonText(it, "$indent  ")}" }
    is JsonObject -> e.entries.joinToString("\n") { (k, v) ->
        if (v is JsonPrimitive || v is JsonNull) "$indent$k: ${jsonText(v)}" else "$indent$k:\n${jsonText(v, "$indent  ")}"
    }
}
