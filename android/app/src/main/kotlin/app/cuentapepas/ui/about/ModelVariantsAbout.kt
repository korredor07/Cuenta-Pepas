package app.cuentapepas.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.ModelVariant
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.inference.ModelException
import app.cuentapepas.ui.common.ErrorInfo
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.describeError
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services

/** Metrics every variant should report; shown as "sin dato" when missing or null. */
private val EXPECTED_METRICS = listOf("val_map50", "val_map50_95", "val_recall_iou50", "val_recall_small", "val_n_images")

private class VariantAbout(val variant: ModelVariant, val spec: ModelSpec?, val error: ErrorInfo?)

private class VariantsAboutData(val activeId: String, val active: ModelVariant?, val items: List<VariantAbout>)

/** About section: the detection resolution in use and the metrics each shipped resolution declares in its model.json. */
@Composable
fun ColumnScope.ModelVariantsAbout() {
    val context = LocalContext.current
    val services = context.services
    val loader = rememberLoader(Unit) {
        val variants = services.variants
        val activeId = services.settings.load().detectionVariant
        VariantsAboutData(
            activeId,
            variants.byId(activeId),
            variants.variants.map { v ->
                try {
                    VariantAbout(v, services.modelSpec(v.id), null)
                } catch (e: ModelException) {
                    VariantAbout(v, null, context.describeError(e))
                }
            },
        )
    }
    LoadView(loader) { d ->
        Text(stringResource(R.string.about_variants_title), style = MaterialTheme.typography.titleMedium)
        val active = d.active
        Text(
            if (active != null) {
                stringResource(R.string.about_variant_active, active.labelEs, active.inputWidth, active.inputHeight)
            } else {
                stringResource(R.string.about_variant_active_unknown, d.activeId)
            },
        )
        for (item in d.items) {
            val v = item.variant
            Column {
                Text(
                    stringResource(R.string.about_variant_item, v.labelEs, v.id, v.inputWidth, v.inputHeight),
                    style = MaterialTheme.typography.titleSmall,
                )
                item.error?.let { ErrorText(it.message, it.detail) }
                item.spec?.let { spec ->
                    val metrics = spec.metrics
                    for (k in EXPECTED_METRICS + (metrics.keys - EXPECTED_METRICS.toSet()).sorted()) {
                        val value = metrics[k]
                        val text = if (k.endsWith("n_images")) value?.let { Fmt.num(it, 0) } ?: Fmt.NO_DATA else Fmt.orNoData(value, 3)
                        Text(stringResource(R.string.about_metric_line, k, text), style = MaterialTheme.typography.bodySmall)
                    }
                    spec.paritySummary?.let {
                        Text(stringResource(R.string.about_variant_parity), style = MaterialTheme.typography.bodySmall)
                        Text(jsonText(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
