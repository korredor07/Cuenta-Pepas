package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.chart.ChartMath
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.stats.Estimate

/**
 * One horizontal bar per maturity class with the share of visible cherries and its confidence interval drawn as a
 * whisker (line with end caps). All rows share one 0-based axis so lengths are comparable. Tapping a row shows
 * "valor % (IC 90 %: bajo–alto %)".
 */
@Composable
fun MaturityShareBars(
    shares: List<Estimate>,
    classNames: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = axisTextStyle(colors.axisText)
    val maxHigh = shares.maxOfOrNull { maxOf(it.ci.high, it.value) } ?: 1.0
    val axis = ChartMath.zeroBasedAxis(maxHigh.coerceIn(0.0, 1.0), 5).let {
        // A share never exceeds 100 %: cap the axis there.
        if (it.max > 1.0) ChartMath.niceAxis(0.0, 1.0, 5) else it
    }
    val tickDecimals = (ChartMath.tickDecimals(axis.step * 100.0))
    var selected by remember(shares) { mutableStateOf<Int?>(null) }

    Column(modifier.semantics { this.contentDescription = contentDescription }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shares.forEachIndexed { i, e ->
            val name = classNames.getOrElse(i) { "Clase ${i + 1}" }
            val color = maturityColor(i)
            Column(
                Modifier.fillMaxWidth().clickable { selected = if (selected == i) null else i }
                    .semantics { this.contentDescription = "$name: ${Fmt.estimatePercent(e)}" },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(Fmt.percent(e.value), style = MaterialTheme.typography.labelLarge)
                }
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val left = 4.dp.toPx()
                    val right = size.width - 4.dp.toPx()
                    val w = right - left
                    fun px(v: Double) = left + w * axis.fraction(v).toFloat()
                    val barH = 12.dp.toPx()
                    val top = (size.height - barH) / 2f
                    drawRoundRect(colors.track, Offset(left, top), Size(w, barH), CornerRadius(3.dp.toPx()))
                    for (t in axis.ticks) drawLine(colors.grid, Offset(px(t), top - 2.dp.toPx()), Offset(px(t), top + barH + 2.dp.toPx()), strokeWidth = 1.dp.toPx())
                    val vx = px(e.value.coerceIn(0.0, axis.max))
                    if (vx > left) drawRect(color, Offset(left, top), Size(vx - left, barH))
                    drawRoundRect(colors.outline, Offset(left, top), Size(w, barH), CornerRadius(3.dp.toPx()), style = Stroke(0.8.dp.toPx()))
                    // Whisker: the interval, clamped into the axis so it never leaves the chart.
                    val (lo, hi) = ChartMath.clampInterval(e.ci.low, e.ci.high, 0.0, axis.max)
                    val cy = size.height / 2f
                    val ink = colors.ink
                    drawLine(ink, Offset(px(lo), cy), Offset(px(hi), cy), strokeWidth = 2.dp.toPx())
                    val cap = 6.dp.toPx()
                    drawLine(ink, Offset(px(lo), cy - cap), Offset(px(lo), cy + cap), strokeWidth = 2.dp.toPx())
                    drawLine(ink, Offset(px(hi), cy - cap), Offset(px(hi), cy + cap), strokeWidth = 2.dp.toPx())
                }
                if (selected == i) {
                    Text(Fmt.estimatePercent(e), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // Shared axis.
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val left = 4.dp.toPx()
            val w = size.width - 8.dp.toPx()
            for (t in axis.ticks) {
                val x = left + w * axis.fraction(t).toFloat()
                drawLabel(measurer, Fmt.num(t * 100.0, tickDecimals) + " %", labelStyle, x, 2.dp.toPx(), vAlign = 0f)
            }
        }
        Text(stringResource(R.string.chart_legend_ci), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected == null) {
            Text(stringResource(R.string.chart_tap_row_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
