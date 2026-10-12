package app.cuentapepas.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelVariants
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.core.store.CorruptDataException
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import app.cuentapepas.ui.common.showError
import app.cuentapepas.ui.common.showInfo
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Kind { NUMBER, INTEGER, FLAG }

private class Field(val key: String, val label: String, val kind: Kind)

private val FIELDS = listOf(
    Field("quality.minSharpness", "Calidad: nitidez mínima", Kind.NUMBER),
    Field("quality.minBrightness", "Calidad: brillo mínimo", Kind.NUMBER),
    Field("quality.maxBrightness", "Calidad: brillo máximo", Kind.NUMBER),
    Field("quality.maxBrightFraction", "Calidad: fracción máxima de píxeles brillantes (0–1)", Kind.NUMBER),
    Field("quality.maxFrameWidthsPerSecond", "Calidad: movimiento máximo (anchos de cuadro por segundo)", Kind.NUMBER),
    Field("quality.onFrames", "Calidad: cuadros seguidos para mostrar un aviso", Kind.INTEGER),
    Field("quality.offFrames", "Calidad: cuadros seguidos para quitar un aviso", Kind.INTEGER),
    Field("detect.scoreThreshold", "Detección: umbral de confianza (0–1)", Kind.NUMBER),
    Field("detect.nmsIou", "Detección: umbral IoU de supresión (0–1)", Kind.NUMBER),
    Field("tracker.highThresh", "Seguimiento: umbral alto", Kind.NUMBER),
    Field("tracker.lowThresh", "Seguimiento: umbral bajo", Kind.NUMBER),
    Field("tracker.newTrackThresh", "Seguimiento: umbral de pista nueva", Kind.NUMBER),
    Field("tracker.matchThresh", "Seguimiento: umbral de asociación", Kind.NUMBER),
    Field("tracker.secondMatchThresh", "Seguimiento: umbral de segunda asociación", Kind.NUMBER),
    Field("tracker.unconfirmedMatchThresh", "Seguimiento: umbral de pistas sin confirmar", Kind.NUMBER),
    Field("tracker.maxTimeLost", "Seguimiento: cuadros máximos sin ver una pista", Kind.INTEGER),
    Field("tracker.fuseScore", "Seguimiento: combinar confianza con IoU", Kind.FLAG),
    Field("counter.minHits", "Conteo: cuadros mínimos para contar una pepa", Kind.INTEGER),
    Field("counter.minMeanScore", "Conteo: confianza media mínima (0–1)", Kind.NUMBER),
    Field("gps.maxAccuracyM", "GPS: precisión máxima aceptada (m)", Kind.NUMBER),
)

private const val VARIANT_KEY = "detection.variant"

private class Loaded(val base: AppSettings, val values: Map<String, String>, val variants: ModelVariants)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(Unit) {
        val variants = services.variants
        val s = services.settings.load()
        // A stored resolution that this build no longer ships is shown (and saved) as the default one.
        val fixed = if (variants.byId(s.detectionVariant) == null) s.copy(detectionVariant = variants.default) else s
        Loaded(s, fixed.snapshot(), variants)
    }
    val snack = remember { SnackbarHostState() }
    val close = stringResource(R.string.snackbar_close)

    // Results appear in the snackbar (visible above the bottom buttons that triggered them), not at the page top.
    ScreenScaffold(stringResource(R.string.settings_title), onBack, snackbar = snack) {
        Text(stringResource(R.string.settings_note), style = MaterialTheme.typography.bodyMedium)
        LoadView(loader) { loaded ->
            val values = remember(loaded) { mutableStateMapOf<String, String>().apply { putAll(loaded.values) } }
            Text(stringResource(R.string.settings_variant_title), style = MaterialTheme.typography.titleSmall)
            VariantSelector(loaded.variants, values[VARIANT_KEY].orEmpty(), onSelect = { values[VARIANT_KEY] = it })
            for (f in FIELDS) {
                val v = values[f.key].orEmpty()
                if (f.kind == Kind.FLAG) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = v == "true", onCheckedChange = { values[f.key] = it.toString() })
                        Text(f.label)
                    }
                } else {
                    OutlinedTextField(
                        value = v,
                        onValueChange = { values[f.key] = it },
                        label = { Text(f.label) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (f.kind == Kind.INTEGER) KeyboardType.Number else KeyboardType.Decimal,
                        ),
                        singleLine = true,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        try {
                            val normalized = values.mapValues { it.value.trim().replace(',', '.') }
                            val parsed = AppSettings.fromMap(normalized, loaded.base)
                            withContext(Dispatchers.IO) { services.settings.save(parsed) }
                            snack.showInfo(context.getString(R.string.settings_saved))
                            loader.reload()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CorruptDataException) {
                            Log.e("Cuentapepas", "Ajustes no válidos", e)
                            val key = e.path.removePrefix("settings:")
                            val label = FIELDS.firstOrNull { it.key == key }?.label
                            snack.showError(
                                if (label != null) {
                                    context.getString(R.string.settings_invalid, label, e.message.orEmpty())
                                } else {
                                    e.message.orEmpty()
                                },
                                close,
                            )
                        } catch (e: Exception) {
                            snack.showError(context.describe(e), close)
                        }
                    }
                }) { Text(stringResource(R.string.common_save)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { services.settings.reset() }
                            snack.showInfo(context.getString(R.string.settings_reset_done))
                            loader.reload()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            snack.showError(context.describe(e), close)
                        }
                    }
                }) { Text(stringResource(R.string.settings_reset)) }
            }
        }
    }
}
