package app.cuentapepas.ui.bench

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.cuentapepas.R
import app.cuentapepas.ui.common.Load
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import app.cuentapepas.ui.settings.VariantSelector
import app.cuentapepas.ui.common.ErrorText
import java.io.File
import java.util.Locale

/** Self-contained benchmark screen. Keeps the screen awake while a run is in progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val view = LocalView.current
    val runner = remember { BenchmarkRunner(context) }
    var minutes by remember { mutableIntStateOf(15) }
    var cpuOnly by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    // Resolution to measure: defaults to the one chosen in Ajustes, changeable here to compare FPS per resolution.
    val variantLoader = rememberLoader(Unit) { services.variants to services.settings.load().detectionVariant }
    var chosenVariant by remember { mutableStateOf<String?>(null) }
    val loadedVariants = (variantLoader.state.value as? Load.Ok)?.value
    val variantId = chosenVariant
        ?: loadedVariants?.let { (vs, current) -> if (vs.byId(current) != null) current else vs.default }
    var progress by remember { mutableStateOf<BenchProgress?>(null) }
    var outcome by remember { mutableStateOf<BenchOutcome?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    // Runner callbacks come from the benchmark thread; Compose state is only written on the main thread.
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) { onDispose { mainHandler.removeCallbacksAndMessages(null) } }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { runner.shutdown() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.bench_title)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.bench_back)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bench_duration), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(1, 5, 15)) {
                    FilterChip(
                        selected = minutes == m,
                        enabled = !running,
                        onClick = { minutes = m },
                        label = { Text(stringResource(R.string.bench_minutes, m)) },
                    )
                }
            }
            Text(stringResource(R.string.bench_variant), style = MaterialTheme.typography.titleSmall)
            LoadView(variantLoader) { (vs, _) ->
                VariantSelector(vs, variantId.orEmpty(), onSelect = { chosenVariant = it }, enabled = !running, showNote = false)
            }
            Text(stringResource(R.string.bench_delegate), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !cpuOnly, enabled = !running, onClick = { cpuOnly = false },
                    label = { Text(stringResource(R.string.bench_delegate_auto)) },
                )
                FilterChip(
                    selected = cpuOnly, enabled = !running, onClick = { cpuOnly = true },
                    label = { Text(stringResource(R.string.bench_delegate_cpu)) },
                )
            }
            if (!running) {
                Button(enabled = variantId != null, onClick = {
                    outcome = null
                    progress = null
                    running = true
                    runner.start(
                        durationSec = minutes * 60,
                        variantId = checkNotNull(variantId),
                        preferGpu = !cpuOnly,
                        onProgress = { mainHandler.post { progress = it } },
                        onFinished = { mainHandler.post { outcome = it; running = false } },
                    )
                }) { Text(stringResource(R.string.bench_start)) }
            } else {
                OutlinedButton(onClick = { runner.stop() }) { Text(stringResource(R.string.bench_stop)) }
            }

            val p = progress
            if (running) {
                Text(
                    if (p == null) {
                        stringResource(R.string.bench_preparing)
                    } else {
                        stringResource(R.string.bench_running, p.elapsedSec.toInt(), p.totalSec.toInt(), p.delegate ?: "?")
                    },
                )
            }
            val doneReal = (outcome as? BenchOutcome.Done)?.result?.summary?.realImages
            if ((doneReal ?: p?.realImages) == false) {
                Text(stringResource(R.string.bench_no_real_images), color = MaterialTheme.colorScheme.error)
            }
            if (running || outcome != null) {
                Text(stringResource(R.string.bench_chart_title), style = MaterialTheme.typography.titleSmall)
                LatencyChart(p?.chart.orEmpty(), p?.totalSec ?: 1.0)
            }

            when (val o = outcome) {
                is BenchOutcome.Failed -> ErrorText(stringResource(R.string.bench_failed, o.message), o.detail)
                is BenchOutcome.Done -> {
                    Results(o.result.summary, o.file) {
                        shareError = try {
                            share(context, o.file)
                            null
                        } catch (e: Exception) {
                            context.getString(R.string.bench_share_failed, context.describe(e))
                        }
                    }
                    shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun LatencyChart(points: List<Pair<Double, Double>>, totalSec: Double) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    if (points.isEmpty()) {
        Text(stringResource(R.string.bench_chart_empty))
        return
    }
    val maxMs = (points.maxOf { it.second } * 1.15).coerceAtLeast(1.0)
    Column {
        Text(String.format(Locale.US, "%.1f ms", maxMs), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            drawLine(axis, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
            val path = Path()
            points.forEachIndexed { i, (t, ms) ->
                val x = (t / totalSec).coerceIn(0.0, 1.0).toFloat() * size.width
                val y = size.height - (ms / maxMs).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 4f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 s", style = MaterialTheme.typography.labelSmall)
            Text("${totalSec.toInt()} s", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Results(s: BenchSummaryJson, file: File, onShare: () -> Unit) {
    val none = stringResource(R.string.bench_no_data)
    fun f(v: Double?, d: Int = 1) = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: none
    Text(stringResource(R.string.bench_results), style = MaterialTheme.typography.titleMedium)
    if (s.stopped) Text(stringResource(R.string.bench_stopped_partial))
    Text(stringResource(R.string.bench_res_latency, f(s.latencyMsMedian), f(s.latencyMsP90)))
    Text(stringResource(R.string.bench_res_fps, f(s.fpsMedian)))
    if (s.trendWindowS != null) {
        Text(stringResource(R.string.bench_res_trend, s.trendWindowS.toInt(), f(s.firstWindowMedianMs), f(s.lastWindowMedianMs)))
    }
    Text(stringResource(R.string.bench_res_battery, f(s.batteryPctPerHour)))
    Text(stringResource(R.string.bench_res_thermal, s.maxThermal?.toString() ?: none))
    Text(stringResource(R.string.bench_res_delegate, s.delegate))
    s.fallbackReason?.let { Text(stringResource(R.string.bench_res_fallback, it)) }
    Text(stringResource(R.string.bench_res_device, listOfNotNull(s.deviceModel, s.socModel).joinToString(" / ")))
    Text(stringResource(R.string.bench_res_model, s.modelId))
    Text(stringResource(R.string.bench_res_variant, s.variant, s.inputWidth, s.inputHeight))
    Text(stringResource(R.string.bench_res_file, file.name), style = MaterialTheme.typography.labelSmall)
    Button(onClick = onShare) { Text(stringResource(R.string.bench_share)) }
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "app.cuentapepas.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.bench_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
