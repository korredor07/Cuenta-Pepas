package app.cuentapepas.ui.summary

import app.cuentapepas.ui.theme.brandCardElevation
import app.cuentapepas.ui.theme.numberStyle
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.cuentapepas.R
import app.cuentapepas.core.chart.ChartMath
import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.Geo
import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.RepeatCheck
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStats
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.ui.charts.MaturityLegend
import app.cuentapepas.ui.charts.PercentBar
import app.cuentapepas.ui.charts.StackedBar
import app.cuentapepas.ui.charts.StackedBarChart
import app.cuentapepas.ui.charts.TableToggle
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.classNames
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private class SummaryData(
    val meta: SessionMeta,
    val plants: List<PlantRecord>,
    val stats: SessionStats?,
    /** Spanish reason when stats.json exists but cannot be read (the rest of the summary is still shown). */
    val statsError: String?,
    val manual: Map<Int, Int>,
    val classNames: List<String>,
    val others: Map<String, SessionMeta>,
    val repeat: RepeatCheck?,
    /** Spanish notes about sessions whose GPS file could not be read: those are unverifiable, not failures. */
    val gpsNotes: List<String>,
    /** `<id>.mp4` followed by `<id>_N.mp4` segments, in recording order. */
    val videos: List<File>,
)

/** Video files of a session: `<id>.mp4` and `<id>_<n>.mp4`, the first segment first, then by segment number. */
private fun videoSegments(dir: File?, sessionId: String): List<File> {
    val files = dir?.listFiles() ?: return emptyList()
    val pattern = Regex("^" + Regex.escape(sessionId) + "(?:_(\\d+))?\\.mp4$")
    return files.filter { it.isFile }
        .mapNotNull { f -> pattern.matchEntire(f.name)?.let { m -> (m.groupValues[1].toIntOrNull() ?: 0) to f } }
        .sortedBy { it.first }
        .map { it.second }
}

@Composable
fun SessionSummaryScreen(sessionId: String, onDone: (lotId: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(sessionId) {
        val store = services.store
        val meta = store.session(sessionId)
        val plants = store.plants(sessionId).sortedBy { it.plantNumber }
        var stats: SessionStats? = null
        var statsError: String? = null
        try {
            stats = store.stats(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A corrupt stats file must not hide the counts: show it as unreadable.
            Log.e(TAG, "Cannot read stats of $sessionId", e)
            statsError = context.getString(R.string.summary_stats_unreadable, context.describe(e))
        }
        val manual = store.manualCounts(meta.lotId).filter { it.row == meta.row }
            .groupBy { it.plantNumber }.mapValues { (_, v) -> v.maxBy { it.enteredAtMs }.count }
        val others = store.sessions(meta.lotId).filter { it.id != sessionId && it.status == SessionStatus.COMPLETED }
        val gpsNotes = ArrayList<String>()
        var repeat: RepeatCheck? = null
        if (meta.status == SessionStatus.COMPLETED && others.isNotEmpty()) {
            val maxAcc = meta.settings["gps.maxAccuracyM"]?.toFloatOrNull() ?: 10f
            val own: List<GpsPoint>? = try {
                store.gps(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cannot read GPS of $sessionId", e)
                gpsNotes += context.getString(R.string.summary_gps_own_unreadable, context.describe(e))
                null
            }
            if (own != null) {
                val tracks = LinkedHashMap<String, List<GpsPoint>>()
                for (o in others) {
                    try {
                        tracks[o.id] = store.gps(o.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // That session cannot be verified; the others still can.
                        Log.e(TAG, "Cannot read GPS of ${o.id}", e)
                        gpsNotes += context.getString(R.string.summary_gps_unreadable, gpsLabel(context, o), context.describe(e))
                    }
                }
                repeat = Geo.possibleRepeats(own, tracks, maxAccuracyM = maxAcc)
            }
        }
        val videos = videoSegments(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), sessionId)
        val numClasses = plants.firstOrNull()?.counts?.perClass?.size ?: 0
        SummaryData(
            meta, plants, stats, statsError, manual, services.classNames(numClasses),
            others.associateBy { it.id }, repeat, gpsNotes, videos,
        )
    }

    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }

    ScreenScaffold(stringResource(R.string.summary_title), onBack) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { ErrorText(it) }
        LoadView(loader) { d ->
            val status = stringResource(
                when (d.meta.status) {
                    SessionStatus.ACTIVE -> R.string.session_status_ACTIVE
                    SessionStatus.COMPLETED -> R.string.session_status_COMPLETED
                    SessionStatus.REPLACED -> R.string.session_status_REPLACED
                    SessionStatus.ABANDONED -> R.string.session_status_ABANDONED
                },
            )
            Text(
                stringResource(R.string.summary_header, d.meta.row, d.meta.side.name, Fmt.dateTime(d.meta.startedAtMs)),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.summary_status, status))

            GpsCheck(d, dismissed,
                onDistinct = { dismissed = dismissed + it },
                onMark = { otherId ->
                    scope.launch {
                        try {
                            val fresh = services.store.sessions(d.meta.lotId)
                            val other = fresh.firstOrNull { it.id == otherId }
                            val me = fresh.firstOrNull { it.id == d.meta.id }
                            val label = d.others[otherId]?.let { gpsLabel(context, it) } ?: otherId
                            if (other?.status == SessionStatus.COMPLETED && me?.status == SessionStatus.COMPLETED) {
                                services.store.markReplaced(otherId)
                                message = context.getString(R.string.gps_replaced_ok, label)
                                error = null
                                loader.reload()
                            } else {
                                error = context.getString(R.string.gps_cannot_replace, label)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Marking a pass as replaced failed", e)
                            error = context.describe(e)
                        }
                    }
                },
            )

            for (note in d.gpsNotes) Text(note, color = MaterialTheme.colorScheme.tertiary)

            PlantsTable(d, onSaved = { n ->
                message = context.getString(R.string.summary_manual_saved, n)
                error = null
                loader.reload()
            }, onError = { error = it })

            Text(stringResource(R.string.summary_stats_title), style = MaterialTheme.typography.titleMedium)
            StatsBlock(d.stats, d.statsError)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = services.exporter.exportSession(sessionId)
                            context.startActivity(services.exporter.shareIntent(file))
                            error = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Export failed", e)
                            error = context.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(if (busy) R.string.summary_exporting else R.string.summary_export)) }
                if (d.meta.evaluationMode) {
                    OutlinedButton(onClick = {
                        if (d.videos.isEmpty()) {
                            error = context.getString(R.string.summary_videos_missing)
                        } else try {
                            val uris = ArrayList<Uri>(
                                d.videos.map { FileProvider.getUriForFile(context, "app.cuentapepas.fileprovider", it) },
                            )
                            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "video/mp4"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(send, context.getString(R.string.summary_share_video_title))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                            error = null
                        } catch (e: Exception) {
                            Log.e(TAG, "Sharing the video failed", e)
                            error = context.describe(e)
                        }
                    }) { Text(stringResource(R.string.summary_share_video_n, d.videos.size)) }
                }
                OutlinedButton(onClick = { onDone(d.meta.lotId) }) { Text(stringResource(R.string.summary_done)) }
            }
        }
    }
}

private fun gpsLabel(context: android.content.Context, s: SessionMeta): String =
    context.getString(R.string.gps_session_label, s.row, s.side.name, Fmt.dateTime(s.endedAtMs ?: s.startedAtMs))

@Composable
private fun GpsCheck(
    d: SummaryData,
    dismissed: Set<String>,
    onDistinct: (String) -> Unit,
    onMark: (String) -> Unit,
) {
    val context = LocalContext.current
    val check = d.repeat ?: return
    for (id in check.repeats) {
        if (id in dismissed) continue
        val other = d.others[id] ?: continue
        val label = gpsLabel(context, other)
        Card(Modifier.fillMaxWidth(), elevation = brandCardElevation()) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gps_check_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.gps_repeat, label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onMark(id) }) { Text(stringResource(R.string.gps_mark_replacement, label)) }
                    OutlinedButton(onClick = { onDistinct(id) }) { Text(stringResource(R.string.gps_distinct)) }
                }
            }
        }
    }
    for (id in check.unverifiable) {
        val other = d.others[id] ?: continue
        Text(stringResource(R.string.gps_unverifiable, gpsLabel(context, other)), color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun PlantsTable(d: SummaryData, onSaved: (Int) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.summary_plants_title), style = MaterialTheme.typography.titleMedium)
    if (d.plants.isEmpty()) {
        val noFrames = d.stats == null || d.stats.framesProcessed == 0
        Text(
            stringResource(
                when {
                    d.meta.status == SessionStatus.ABANDONED && noFrames -> R.string.summary_no_frames
                    d.meta.status == SessionStatus.ABANDONED -> R.string.summary_no_counted_plants
                    else -> R.string.summary_no_plants
                },
            ),
        )
        return
    }
    val calibration = d.meta.isCalibrationSample
    val numClasses = d.plants.first().counts.perClass.size
    val dash = stringResource(R.string.summary_plant_no_data)
    val entries = remember(d) { mutableStateMapOf<Int, String>().apply { d.manual.forEach { (k, v) -> put(k, v.toString()) } } }
    val cellW = 72.dp
    // Plants observed in zero frames are "no data", never zeros: shown as a dash and left out of the totals.
    val withData = d.plants.filter { it.hasData }

    SessionCharts(d, withData)
    // The exact numbers stay one tap away. Calibration samples keep the table open: the manual-count fields live there.
    TableToggle(initiallyOpen = calibration) {
        // 360dp cannot hold plant + classes + total + manual columns: the table scrolls sideways as one block.
        androidx.compose.foundation.layout.Column(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.summary_col_plant), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
            d.classNames.forEach { Text(it, Modifier.width(cellW), style = MaterialTheme.typography.labelLarge) }
            Text(stringResource(R.string.summary_col_total), Modifier.width(cellW), style = MaterialTheme.typography.labelLarge)
            if (calibration) Text(stringResource(R.string.summary_col_manual), Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider()
        for (p in d.plants) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(p.plantNumber.toString(), Modifier.width(64.dp), style = numberStyle)
                for (c in 0 until numClasses) Text(if (p.hasData) p.counts[c].toString() else dash, Modifier.width(cellW), style = numberStyle)
                Text(if (p.hasData) p.counts.total.toString() else dash, Modifier.width(cellW), style = numberStyle)
                if (calibration) {
                    OutlinedTextField(
                        value = entries[p.plantNumber].orEmpty(),
                        onValueChange = { entries[p.plantNumber] = it },
                        modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = if (p === d.plants.last()) ImeAction.Done else ImeAction.Next,
                        ),
                        singleLine = true,
                    )
                }
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.summary_total_row), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
            for (c in 0 until numClasses) {
                Text(
                    if (withData.isEmpty()) dash else withData.sumOf { it.counts[c] }.toString(),
                    Modifier.width(cellW), style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                if (withData.isEmpty()) dash else withData.sumOf { it.counts.total }.toString(),
                Modifier.width(cellW), style = MaterialTheme.typography.labelLarge,
            )
        }
        } // end sideways-scrolling table
        if (calibration) {
            Text(stringResource(R.string.summary_manual_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                // Validate everything first. A blank entry over a saved count is a request to clear it.
                val changes = ArrayList<Pair<Int, Int>>()
                val clears = ArrayList<Int>()
                for (p in d.plants) {
                    val raw = entries[p.plantNumber]?.trim().orEmpty()
                    if (raw.isEmpty()) {
                        if (d.manual[p.plantNumber] != null) clears += p.plantNumber
                        continue
                    }
                    val v = raw.toIntOrNull()?.takeIf { it >= 0 }
                    if (v == null) {
                        onError(context.getString(R.string.summary_manual_invalid, p.plantNumber))
                        return@OutlinedButton
                    }
                    if (d.manual[p.plantNumber] != v) changes += p.plantNumber to v
                }
                scope.launch {
                    try {
                        for ((plant, count) in changes) services.store.setManualCount(d.meta.lotId, d.meta.row, plant, count)
                        // A cleared field removes the plant's manual count (no count = "sin dato", never 0).
                        for (plant in clears) services.store.clearManualCount(d.meta.lotId, d.meta.row, plant)
                        onSaved(changes.size + clears.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Saving manual counts failed", e)
                        onError(context.describe(e))
                    }
                }
            }) { Text(stringResource(R.string.summary_manual_save)) }
        }
    }
}

/**
 * Charts of a session: visible cherries per plant stacked by maturity (plants seen in zero frames are hatched "sin dato",
 * never zero bars) and the maturity share of the whole pass.
 */
@Composable
private fun SessionCharts(d: SummaryData, withData: List<PlantRecord>) {
    val context = LocalContext.current
    val numClasses = d.plants.first().counts.perClass.size
    val names = d.classNames
    fun className(i: Int) = names.getOrElse(i) { "Clase ${i + 1}" }
    val bars = d.plants.map { p ->
        val detail = if (p.hasData) {
            context.getString(
                R.string.chart_plant_detail, p.plantNumber,
                (0 until numClasses).joinToString(" · ") { c -> "${className(c)} ${p.counts[c]}" }, p.counts.total,
            )
        } else {
            context.getString(R.string.chart_plant_no_data, p.plantNumber)
        }
        StackedBar(p.plantNumber.toString(), if (p.hasData) p.counts.perClass else null, detail)
    }
    val totals = withData.map { it.counts.total }
    val stackedDesc = if (withData.isEmpty()) {
        context.getString(R.string.chart_stacked_desc_none, d.plants.size)
    } else {
        context.getString(
            R.string.chart_stacked_desc, withData.size, d.plants.size - withData.size,
            Fmt.num(totals.min().toDouble(), 0), Fmt.num(totals.max().toDouble(), 0),
        )
    }
    Text(stringResource(R.string.chart_stacked_title), style = MaterialTheme.typography.titleSmall)
    StackedBarChart(bars, names, stackedDesc)
    MaturityLegend(names, showNoData = withData.size < d.plants.size)

    val shareCounts = if (withData.isEmpty()) null else withData.map { it.counts }.reduce(ClassCounts::plus).perClass
    val fractions = shareCounts?.let { ChartMath.fractions(it) }
    val shareDesc = if (shareCounts == null || fractions == null) {
        context.getString(R.string.chart_share_session_none)
    } else {
        context.getString(
            R.string.chart_share_session_desc,
            (0 until numClasses).joinToString(", ") { c -> "${className(c)} ${Fmt.percent(fractions[c])}" },
        )
    }
    Text(stringResource(R.string.chart_share_session_title), style = MaterialTheme.typography.titleSmall)
    PercentBar(shareCounts, names, shareDesc)
}

@Composable
private fun StatsBlock(s: SessionStats?, statsError: String?) {
    if (s == null) {
        if (statsError != null) ErrorText(statsError) else Text(stringResource(R.string.summary_stats_missing))
        return
    }
    val context = LocalContext.current
    Text(stringResource(R.string.stat_duration, Fmt.duration(s.durationS)))
    Text(stringResource(R.string.stat_frames, s.framesProcessed))
    Text(stringResource(R.string.stat_frames_errored, Fmt.intOrNoData(s.framesErrored)))
    Text(stringResource(R.string.stat_frames_throttled, Fmt.intOrNoData(s.framesDroppedByThrottle)))
    Text(stringResource(R.string.stat_perf_dropped, Fmt.intOrNoData(s.droppedPerfSamples)))
    Text(stringResource(R.string.stat_fps, Fmt.orNoData(s.fpsMedian), Fmt.orNoData(s.fpsP10)))
    Text(stringResource(R.string.stat_inference, Fmt.orNoData(s.inferenceMsMedian), Fmt.orNoData(s.inferenceMsP90)))
    // "none" is stored when the detector never became ready: that is "sin dato", not a delegate name.
    Text(stringResource(R.string.stat_delegate, if (s.delegate == "none") Fmt.NO_DATA else s.delegate))
    val thermal = when (s.maxThermalStatus) {
        0 -> R.string.thermal_none
        1 -> R.string.thermal_light
        2 -> R.string.thermal_moderate
        3 -> R.string.thermal_severe
        4 -> R.string.thermal_critical
        5 -> R.string.thermal_emergency
        6 -> R.string.thermal_shutdown
        else -> R.string.thermal_no_data
    }
    Text(stringResource(R.string.stat_thermal, context.getString(thermal)))
    val battery = s.batteryPctPerHour?.let { "${Fmt.num(it)} %" } ?: Fmt.NO_DATA
    Text(stringResource(R.string.stat_battery, battery))
    if (s.warningFraction.isEmpty()) {
        Text(stringResource(R.string.stat_warnings_none))
    } else {
        Text(stringResource(R.string.stat_warnings))
        for ((k, v) in s.warningFraction.toSortedMap()) {
            Text("  " + stringResource(R.string.stat_warning_line, k, Fmt.percent(v)))
        }
    }
    Text(stringResource(R.string.stat_gps, Fmt.orNoData(s.gpsAccuracyMedianM), Fmt.orNoData(s.gpsAccuracyP90M)))
    s.evalLogError?.let { ErrorText(stringResource(R.string.stat_eval_log_error, it)) }
    s.gpsSaveError?.let { ErrorText(stringResource(R.string.stat_gps_save_error, it)) }
    s.videoIssue?.let { ErrorText(stringResource(R.string.stat_video_issue, it)) }
}

private const val TAG = "Cuentapepas"
