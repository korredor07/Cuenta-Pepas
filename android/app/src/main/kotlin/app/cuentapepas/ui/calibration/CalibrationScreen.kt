package app.cuentapepas.ui.calibration

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cuentapepas.R
import app.cuentapepas.core.calibration.CalibrationResult
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.CalibrationSet
import app.cuentapepas.core.calibration.RatioCalibrator
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.core.session.ResolutionIssue
import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.ui.charts.RangeBar
import app.cuentapepas.ui.charts.ScatterPoint
import app.cuentapepas.ui.charts.ScatterWithLine
import app.cuentapepas.ui.charts.TableToggle
import app.cuentapepas.ui.common.CorruptSessionsBanner
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import app.cuentapepas.ui.common.loadLotData

private const val MIN_SAMPLES = 10

private class CalData(val lotName: String, val set: CalibrationSet, val result: CalibrationResult, val issues: List<ResolutionIssue>)

@Composable
fun CalibrationScreen(lotId: String, onBack: () -> Unit) {
    val services = LocalContext.current.services
    val loader = rememberLoader(lotId) {
        val d = services.store.loadLotData(lotId)
        val set = PassResolver.calibrationSamples(d.sessions, d.plants, d.manualCounts)
        // Resolution anomalies (several COMPLETED passes, duplicate plants...) change which records feed the samples.
        val issues = PassResolver.effectivePlants(d.sessions, d.plants).issues
        CalData(d.lot.name, set, RatioCalibrator.fit(set.samples, MIN_SAMPLES), issues)
    }
    ScreenScaffold(stringResource(R.string.cal_title), onBack) {
        CorruptSessionsBanner { loader.reload() }
        LoadView(loader) { d ->
            Text(d.lotName, style = MaterialTheme.typography.titleLarge)
            val r = d.result
            if (!r.usable || r.n < MIN_SAMPLES) {
                Text(stringResource(R.string.cal_none_big), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
                r.reason?.let { Text(it) }
                Text(stringResource(R.string.cal_samples, r.n, MIN_SAMPLES))
                Text(stringResource(R.string.cal_how_to))
            } else {
                Text(Fmt.calibrationStatus(r.n, true), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.cal_samples, r.n, MIN_SAMPLES))
                // k is a factor, not a harvest figure; it still travels with its interval.
                Text(stringResource(R.string.cal_k, "${Fmt.orNoData(r.k, 2)} ${Fmt.interval(r.kCi, 2)}"))
                val kValue = r.k
                val kInterval = r.kCi
                if (kValue != null && kInterval != null) {
                    val kEstimate = Estimate(kValue, kInterval)
                    Text(stringResource(R.string.chart_k_title), style = MaterialTheme.typography.titleSmall)
                    RangeBar(
                        kEstimate, 2,
                        stringResource(R.string.chart_range_desc, stringResource(R.string.chart_k_title), Fmt.estimate(kEstimate, 2)),
                        includeZero = false,
                    )
                    Text(stringResource(R.string.chart_k_caption), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.cal_plant_factor, Fmt.interval(r.plantFactorInterval, 2)))
                Text(stringResource(R.string.cal_loocv, Fmt.percentOrNoData(r.loocvMeanAbsPctError)))
            }
            Text(stringResource(R.string.cal_explain), style = MaterialTheme.typography.bodyMedium)

            if (d.issues.isNotEmpty()) {
                Text(stringResource(R.string.cal_issues_title), style = MaterialTheme.typography.titleMedium)
                for (issue in d.issues) Text(issue.message, color = MaterialTheme.colorScheme.error)
            }
            if (d.set.skipped.isNotEmpty()) {
                Text(stringResource(R.string.cal_skipped_title), style = MaterialTheme.typography.titleMedium)
                for (s in d.set.skipped) Text(stringResource(R.string.cal_skipped_line, s.plantId, s.reason))
            }
            if (d.set.samples.isNotEmpty()) {
                Text(stringResource(R.string.cal_samples_title), style = MaterialTheme.typography.titleMedium)
                // Without a usable fit only the points are drawn: no line is invented.
                val kValue = r.k
                val fitted = r.usable && r.n >= MIN_SAMPLES && kValue != null
                val points = d.set.samples.map { ScatterPoint(it.plantId, it.visible, it.manual) }
                val lineDesc = if (fitted && kValue != null) {
                    stringResource(R.string.chart_scatter_desc_line, "${Fmt.num(kValue, 2)} ${Fmt.interval(r.kCi, 2)}")
                } else {
                    stringResource(R.string.chart_scatter_desc_noline)
                }
                Text(stringResource(R.string.chart_scatter_title), style = MaterialTheme.typography.titleSmall)
                ScatterWithLine(
                    points, if (fitted) kValue else null, if (fitted) r.kCi else null,
                    stringResource(R.string.chart_scatter_desc, points.size, lineDesc),
                )
                TableToggle {
                    for (s in d.set.samples) Text(stringResource(R.string.cal_sample_line, s.plantId, s.visible, s.manual))
                }
            }
        }
    }
}
