package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.chart.ChartMath
import android.util.Log
import app.cuentapepas.core.chart.ChartGuard
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.stats.Interval

/** One sample plant: [x] cherries seen by the detector, [y] cherries counted by hand. */
class ScatterPoint(val label: String, val x: Int, val y: Int)

/**
 * Scatter of manual count (y) against visible cherries (x) with the fitted line y = k·x and the band between
 * y = kLow·x and y = kHigh·x (the interval of k). [k] and [kCi] are null when no usable fit exists: then only the points
 * are drawn. Tapping a point shows its exact values.
 */
@Composable
fun ScatterWithLine(
    points: List<ScatterPoint>,
    k: Double?,
    kCi: Interval?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // A fit that cannot be drawn is reported once and dropped from the chart; the points still show.
    val fitOk = k == null || ChartGuard.drawableFit(k, kCi)
    if (!fitOk) Log.w("Cuentapepas", "Recta de ajuste omitida: k=$k, IC=$kCi no se puede dibujar")
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = axisTextStyle(colors.axisText)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val xAxis = remember(points) { ChartMath.zeroBasedAxis(points.maxOfOrNull { it.x }?.toDouble() ?: 0.0, 5) }
    val yAxis = remember(points) { ChartMath.zeroBasedAxis(points.maxOfOrNull { it.y }?.toDouble() ?: 0.0, 5) }
    val leftPad = 40.dp
    val bottomPad = 24.dp
    val topPad = 8.dp
    val rightPad = 12.dp

    Column(modifier.semantics { this.contentDescription = contentDescription }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chart_scatter_y), style = MaterialTheme.typography.labelSmall, color = colors.axisText)
        Canvas(
            Modifier.fillMaxWidth().height(240.dp).pointerInput(points, xAxis, yAxis) {
                detectTapGestures { pos ->
                    val l = leftPad.toPx()
                    val t = topPad.toPx()
                    val w = size.width - l - rightPad.toPx()
                    val h = size.height - t - bottomPad.toPx()
                    val xs = FloatArray(points.size) { l + w * xAxis.fraction(points[it].x.toDouble()).toFloat() }
                    val ys = FloatArray(points.size) { t + h * (1f - yAxis.fraction(points[it].y.toDouble()).toFloat()) }
                    val hit = ChartMath.nearest(xs, ys, pos.x, pos.y, 28.dp.toPx())
                    selected = if (hit == selected) null else hit
                }
            },
        ) {
            val l = leftPad.toPx()
            val t = topPad.toPx()
            val w = size.width - l - rightPad.toPx()
            val h = size.height - t - bottomPad.toPx()
            fun px(x: Double) = l + w * xAxis.fraction(x).toFloat()
            fun py(y: Double) = t + h * (1f - yAxis.fraction(y).toFloat())
            for (tick in yAxis.ticks) {
                drawLine(colors.grid, Offset(l, py(tick)), Offset(l + w, py(tick)), strokeWidth = 1.dp.toPx())
                drawLabel(measurer, Fmt.num(tick, ChartMath.tickDecimals(yAxis.step)), labelStyle, l - 4.dp.toPx(), py(tick), hAlign = 1f)
            }
            for (tick in xAxis.ticks) {
                drawLine(colors.grid, Offset(px(tick), t), Offset(px(tick), t + h), strokeWidth = 1.dp.toPx())
                drawLabel(measurer, Fmt.num(tick, ChartMath.tickDecimals(xAxis.step)), labelStyle, px(tick), t + h + 4.dp.toPx(), vAlign = 0f)
            }
            drawLine(colors.outline, Offset(l, t + h), Offset(l + w, t + h), strokeWidth = 1.dp.toPx())
            drawLine(colors.outline, Offset(l, t), Offset(l, t + h), strokeWidth = 1.dp.toPx())

            if (k != null && fitOk && k >= 0.0 && xAxis.max > 0.0 && yAxis.max > 0.0) {
                if (kCi != null && kCi.low >= 0.0) {
                    val poly = ChartMath.bandPolygon(kCi.low, kCi.high, xAxis.max, yAxis.max)
                    val path = Path()
                    poly.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y)) }
                    path.close()
                    drawPath(path, color.copy(alpha = 0.22f))
                }
                val end = ChartMath.rayEnd(k, xAxis.max, yAxis.max)
                drawLine(color, Offset(px(0.0), py(0.0)), Offset(px(end.first), py(end.second)), strokeWidth = 2.5.dp.toPx())
            }
            points.forEachIndexed { i, p ->
                val c = Offset(px(p.x.toDouble()), py(p.y.toDouble()))
                drawCircle(colors.ink.copy(alpha = 0.85f), 4.5.dp.toPx(), c)
                if (selected == i) drawCircle(colors.ink, 10.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            stringResource(R.string.chart_scatter_x),
            style = MaterialTheme.typography.labelSmall,
            color = colors.axisText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        if (!fitOk) {
            Text("—  " + stringResource(R.string.chart_not_drawable), style = MaterialTheme.typography.bodySmall)
        }
        if (k != null && fitOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(22.dp).height(10.dp)) {
                    drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 2.5.dp.toPx())
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chart_scatter_line), style = MaterialTheme.typography.labelMedium)
                if (kCi != null) {
                    Spacer(Modifier.width(14.dp))
                    Canvas(Modifier.width(14.dp).height(10.dp)) { drawRect(color.copy(alpha = 0.28f)) }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chart_scatter_band), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        val sel = selected
        if (sel != null && sel in points.indices) {
            val p = points[sel]
            Text(stringResource(R.string.chart_scatter_point, p.label, p.x, p.y), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.chart_tap_point_hint), style = MaterialTheme.typography.bodySmall, color = colors.axisText)
        }
    }
}
