package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.cuentapepas.core.chart.ChartMath
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.stats.Estimate
import kotlin.math.max
import kotlin.math.min

/**
 * An estimate drawn on a horizontal axis: a thick band for its confidence interval, a marker for the point value and
 * the exact "valor (IC 90 %: bajo–alto)" text underneath. The interval is always drawn. With [includeZero] the axis
 * starts at 0 so lengths are proportional (counts); factors such as k use a tight axis instead.
 */
@Composable
fun RangeBar(
    estimate: Estimate,
    decimals: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    includeZero: Boolean = true,
) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = axisTextStyle(colors.axisText)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = colors.ink)
    val axis = ChartMath.estimateDomain(estimate.value, estimate.ci.low, estimate.ci.high, includeZero)
    val tickDecimals = ChartMath.tickDecimals(axis.step)
    Column(modifier.semantics { this.contentDescription = contentDescription }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val left = 12.dp.toPx()
            val right = size.width - 12.dp.toPx()
            val w = right - left
            fun px(v: Double) = left + w * axis.fraction(v).toFloat()
            val bandTop = 26.dp.toPx()
            val bandH = 18.dp.toPx()
            val axisY = bandTop + bandH + 6.dp.toPx()
            // Gridlines and tick labels.
            for (t in axis.ticks) {
                val x = px(t)
                drawLine(colors.grid, Offset(x, bandTop - 4.dp.toPx()), Offset(x, axisY), strokeWidth = 1.dp.toPx())
                drawLabel(measurer, Fmt.num(t, tickDecimals), labelStyle, x, axisY + 3.dp.toPx(), vAlign = 0f)
            }
            drawLine(colors.outline, Offset(left, axisY), Offset(right, axisY), strokeWidth = 1.dp.toPx())
            // Confidence interval band.
            val x0 = px(min(estimate.ci.low, estimate.ci.high))
            val x1 = px(max(estimate.ci.low, estimate.ci.high))
            val bandW = max(x1 - x0, 3.dp.toPx())
            drawRoundRect(color.copy(alpha = 0.30f), Offset(x0, bandTop), Size(bandW, bandH), CornerRadius(4.dp.toPx()))
            drawRoundRect(color, Offset(x0, bandTop), Size(bandW, bandH), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            // Point value marker, with its number above.
            val xv = px(estimate.value)
            drawLine(colors.ink, Offset(xv, bandTop - 4.dp.toPx()), Offset(xv, bandTop + bandH + 2.dp.toPx()), strokeWidth = 3.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(xv, bandTop + bandH / 2f))
            drawCircle(colors.ink, 5.dp.toPx(), Offset(xv, bandTop + bandH / 2f), style = Stroke(1.5.dp.toPx()))
            val text = Fmt.num(estimate.value, decimals)
            val measured = measurer.measure(text, valueStyle, maxLines = 1)
            val tx = (xv - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
            drawLabel(measurer, text, valueStyle, tx, bandTop - 6.dp.toPx(), hAlign = 0f, vAlign = 1f)
        }
        Text(Fmt.estimate(estimate, decimals), style = MaterialTheme.typography.bodySmall)
    }
}
