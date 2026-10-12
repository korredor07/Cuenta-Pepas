package app.cuentapepas.ui.estimate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.estimate.LotEstimate
import app.cuentapepas.core.estimate.LotEstimator
import app.cuentapepas.core.estimate.LotInput
import app.cuentapepas.core.estimate.LotStatus
import app.cuentapepas.core.estimate.PlantVisible
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.core.session.Resolution
import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.ui.charts.CoverageRing
import app.cuentapepas.ui.charts.MaturityLegend
import app.cuentapepas.ui.charts.MaturityShareBars
import app.cuentapepas.ui.charts.RangeBar
import app.cuentapepas.ui.charts.TableToggle
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.loadLotData
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.classNames
import app.cuentapepas.ui.common.services
import app.cuentapepas.ui.common.ClassNames
import app.cuentapepas.ui.common.CorruptSessionsBanner
import app.cuentapepas.ui.common.UserMessageException
import app.cuentapepas.ui.common.classNamesInfo

private class EstimateData(val lot: Lot, val resolution: Resolution, val estimate: LotEstimate, val classNames: ClassNames)

@Composable
fun LotEstimateScreen(lotId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val loader = rememberLoader(lotId) {
        val d = services.store.loadLotData(lotId)
        val resolution = PassResolver.effectivePlants(d.sessions, d.plants)
        val samples = PassResolver.calibrationSamples(d.sessions, d.plants, d.manualCounts).samples
        val plants = resolution.plants.map { (key, sides) ->
            PlantVisible("${key.row}/${key.plantNumber}", sides.values.map { it.counts })
        }
        val classSizes = resolution.plants.values.flatMap { it.values }.map { it.counts.perClass.size }.toSet()
        if (classSizes.size > 1) throw UserMessageException(context.getString(R.string.error_models_mismatch))
        val estimate = try {
            LotEstimator.estimate(LotInput(d.lot.totalPlants, plants, samples, d.lot.cherriesPerKg))
        } catch (e: IllegalArgumentException) {
            // Class-count mismatch between plants and calibration samples (or another model/taxonomy mix-up).
            throw UserMessageException(context.getString(R.string.error_models_mismatch), e)
        }
        EstimateData(d.lot, resolution, estimate, services.classNamesInfo(classSizes.firstOrNull() ?: 0))
    }
    ScreenScaffold(stringResource(R.string.est_title), onBack) {
        CorruptSessionsBanner { loader.reload() }
        LoadView(loader) { d ->
            val e = d.estimate
            d.classNames.noticeRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Text(d.lot.name, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(
                    when (e.status) {
                        LotStatus.INSUFFICIENT_EVIDENCE -> R.string.est_status_INSUFFICIENT_EVIDENCE
                        LotStatus.VISIBLE_ONLY -> R.string.est_status_VISIBLE_ONLY
                        LotStatus.CALIBRATED -> R.string.est_status_CALIBRATED
                    },
                ),
                style = MaterialTheme.typography.displaySmall,
                color = if (e.status == LotStatus.CALIBRATED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(Fmt.calibrationStatus(e.calibration.n, e.calibration.usable))

            // Every figure carries its interval; null stays "sin dato" and is never shown as 0.
            val calibrated = e.status == LotStatus.CALIBRATED
            val vppRes = if (calibrated) R.string.est_visible_per_plant_cal else R.string.est_visible_per_plant
            val vppCaption = stringResource(if (calibrated) R.string.chart_est_visible_caption_cal else R.string.chart_est_visible_caption)
            val totalCaption = stringResource(R.string.chart_est_total_caption)
            val kgCaption = stringResource(R.string.chart_est_kg_caption)

            // Headline: the harvest figure when it exists, otherwise the visible cherries per plant (never called harvest).
            when {
                e.totalCherries != null -> {
                    EstimateHeadline(totalCaption, e.totalCherries!!, 0)
                    e.totalKg?.let { EstimateHeadline(kgCaption, it, 1) }
                    EstimateRange(vppCaption, e.visiblePerPlant, 1, small = true)
                }
                else -> {
                    EstimateRange(vppCaption, e.visiblePerPlant, 1, small = false)
                    if (calibrated) Text(stringResource(R.string.est_total_cherries, Fmt.NO_DATA))
                }
            }

            CoverageRing(e.plantsVisited, d.lot.totalPlants, e.coverage)

            val shares = e.maturityShare
            if (shares == null) {
                Text(stringResource(R.string.est_maturity_none))
            } else {
                Text(stringResource(R.string.est_maturity_title), style = MaterialTheme.typography.titleMedium)
                val names = d.classNames.names
                val desc = stringResource(
                    R.string.chart_share_lot_desc,
                    shares.indices.joinToString("; ") { i -> "${names.getOrElse(i) { "Clase ${i + 1}" }} ${Fmt.estimatePercent(shares[i])}" },
                )
                MaturityShareBars(shares, names, desc)
                MaturityLegend(names)
            }

            // The exact numbers behind the charts.
            TableToggle {
                Text(stringResource(R.string.est_visited, e.plantsVisited))
                Text(Fmt.coverage(e.plantsVisited, d.lot.totalPlants, e.coverage))
                Text(stringResource(vppRes, Fmt.estimateOrNoData(e.visiblePerPlant)))
                Text(stringResource(R.string.est_total_cherries, Fmt.estimateOrNoData(e.totalCherries, 0)))
                Text(stringResource(R.string.est_total_kg, Fmt.estimateOrNoData(e.totalKg, 1)))
                shares?.forEachIndexed { i, s ->
                    Text("${d.classNames.names.getOrElse(i) { "Clase ${i + 1}" }}: ${Fmt.estimatePercent(s)}")
                }
            }

            Text(stringResource(R.string.est_walked_title), style = MaterialTheme.typography.titleMedium)
            val byRow = d.resolution.plants.entries.groupBy { it.key.row }.toSortedMap()
            if (byRow.isEmpty()) Text(stringResource(R.string.est_walked_none))
            for ((row, entries) in byRow) {
                val sideText = app.cuentapepas.core.session.Side.entries.mapNotNull { side ->
                    val count = entries.count { side in it.value }
                    if (count > 0) stringResource(R.string.est_walked_side, side.name, count) else null
                }.joinToString(", ")
                Text(stringResource(R.string.est_walked_line, row, sideText))
            }

            if (d.resolution.issues.isNotEmpty()) {
                Text(stringResource(R.string.est_issues_title), style = MaterialTheme.typography.titleMedium)
                for (issue in d.resolution.issues) Text(issue.message, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.est_notes_title), style = MaterialTheme.typography.titleMedium)
            for (note in e.notes) Text(note)
        }
    }
}

/** Big number (point value) with its caption and the interval drawn as a [RangeBar]. */
@Composable
private fun EstimateHeadline(caption: String, estimate: Estimate, decimals: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(Fmt.num(estimate.value, decimals), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text(caption, style = MaterialTheme.typography.titleSmall)
        RangeBar(
            estimate, decimals,
            stringResource(R.string.chart_range_desc, caption, Fmt.estimate(estimate, decimals)),
        )
    }
}

/** Estimate shown as a range bar only (visible cherries per plant); null is "sin dato", never an empty or zero bar. */
@Composable
private fun EstimateRange(caption: String, estimate: Estimate?, decimals: Int, small: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (estimate == null) {
            Text(stringResource(R.string.chart_range_no_data, caption), style = MaterialTheme.typography.titleSmall)
            return
        }
        if (!small) Text(Fmt.num(estimate.value, decimals), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.error)
        Text(caption, style = MaterialTheme.typography.titleSmall)
        RangeBar(
            estimate, decimals,
            stringResource(R.string.chart_range_desc, caption, Fmt.estimate(estimate, decimals)),
            color = if (small) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
