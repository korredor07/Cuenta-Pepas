package app.cuentapepas.ui.about

import androidx.compose.material3.MaterialTheme
import android.util.Log
import app.cuentapepas.ui.theme.Brand
import app.cuentapepas.ui.common.CloveranceLogo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.cuentapepas.data.Diagnostics
import app.cuentapepas.ui.common.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Brand.Ink).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CloveranceLogo(36.dp)
            Text(stringResource(R.string.about_credit), style = MaterialTheme.typography.bodyMedium, color = Brand.Paper)
        }
        var diagBusy by remember { mutableStateOf(false) }
        var diagError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodySmall)
        Button(enabled = !diagBusy, onClick = {
            diagBusy = true
            scope.launch {
                try {
                    val zip = Diagnostics.build(context, services)
                    context.startActivity(Diagnostics.shareIntent(context, zip))
                    diagError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Cuentapepas", "Diagnóstico falló", e)
                    diagError = context.getString(R.string.diag_failed, context.describe(e))
                } finally {
                    diagBusy = false
                }
            }
        }) { Text(stringResource(if (diagBusy) R.string.diag_building else R.string.diag_share)) }
        diagError?.let { ErrorText(it) }
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
    is JsonArray -> e.joinToString("\n") { "$indentâ€¢ ${jsonText(it, "$indent  ")}" }
    is JsonObject -> e.entries.joinToString("\n") { (k, v) ->
        if (v is JsonPrimitive || v is JsonNull) "$indent$k: ${jsonText(v)}" else "$indent$k:\n${jsonText(v, "$indent  ")}"
    }
}
