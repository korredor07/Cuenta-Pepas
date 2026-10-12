package app.cuentapepas.ui.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.chart.ChartMath
import app.cuentapepas.core.format.Fmt

/**
 * 100 % horizontal bar with the maturity share of [counts]. Whole-percent labels go inside the segments that are wide
 * enough; tapping a segment shows its exact count and percentage. When the total is 0 there is no share: the bar is
 * hatched and shows "—", never a 0 % bar.
 */
@Composable
fun PercentBar(
    counts: IntArray?,
    classNames: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    val fractions = counts?.let { ChartMath.fractions(it) }
    var selected by remember(counts?.toList()) { mutableStateOf<Int?>(null) }
    Column(modifier.semantics { this.contentDescription = contentDescription }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (counts == null || fractions == null) {
            Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(32.dp)) { drawHatch(Offset.Zero, size, colors.noData) }
                Text(DASH, style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.chart_share_session_none), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val percents = ChartMath.roundedPercents(fractions)
        Row(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(6.dp)),
        ) {
            for (i in counts.indices) {
                if (counts[i] <= 0) continue
                val bg = maturityColor(i)
                Box(
                    Modifier.weight(counts[i].toFloat()).height(32.dp).background(bg)
                        .then(if (selected == i) Modifier.border(2.dp, colors.ink) else Modifier)
                        .clickable { selected = if (selected == i) null else i },
                    contentAlignment = Alignment.Center,
                ) {
                    // A label only fits when the segment has about 8 % of the bar or more.
                    if (fractions[i] >= 0.08) {
                        Text("${percents[i]} %", color = onColor(bg), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
        val sel = selected
        if (sel != null) {
            Text(
                stringResource(R.string.chart_share_segment_detail, classNames.getOrElse(sel) { "Clase ${sel + 1}" }, counts[sel], Fmt.percent(fractions[sel])),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                stringResource(R.string.chart_tap_bar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
