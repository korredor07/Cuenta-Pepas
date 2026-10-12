package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.chart.ChartMath
import app.cuentapepas.core.format.Fmt
import kotlin.math.ceil
import kotlin.math.max

/**
 * One bar of a [StackedBarChart]. [counts] holds one value per maturity class; null means "sin dato" and is drawn as a
 * hatched placeholder, never as a zero bar. [detail] is the exact text shown when the bar is tapped.
 */
class StackedBar(val label: String, val counts: IntArray?, val detail: String)

/**
 * Vertical stacked bars (one segment per maturity class, same colours as the live overlay). Scrolls horizontally when
 * the bars would get too narrow. Tapping a bar shows its exact values under the chart.
 */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    classNames: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 170.dp,
    showHint: Boolean = true,
) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = axisTextStyle(colors.axisText)
    val totalStyle = axisTextStyle(colors.ink)
    val density = LocalDensity.current
    val surface = MaterialTheme.colorScheme.surface
    var selected by remember(bars) { mutableStateOf<Int?>(null) }

    val maxTotal = bars.maxOfOrNull { b -> b.counts?.sumOf { it.toLong() }?.toDouble() ?: 0.0 } ?: 0.0
    val axis = remember(maxTotal) { ChartMath.zeroBasedAxis(maxTotal, 5) }
    val topPad = 16.dp
    val bottomPad = 20.dp
    val axisWidth = 36.dp
    val totalHeight = plotHeight + topPad + bottomPad

    Column(modifier.semantics { this.contentDescription = contentDescription }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val avail = with(density) { (maxWidth - axisWidth).toPx() }
            val layout = remember(bars.size, avail) {
                ChartMath.barLayout(bars.size, avail, minBar = with(density) { 16.dp.toPx() }, maxBar = with(density) { 40.dp.toPx() })
            }
            Row {
                // Fixed y axis: it stays in place while the bars scroll.
                Canvas(Modifier.width(axisWidth).height(totalHeight)) {
                    val top = topPad.toPx()
                    val h = plotHeight.toPx()
                    for (t in axis.ticks) {
                        val y = top + h * (1f - axis.fraction(t).toFloat())
                        drawLabel(measurer, Fmt.num(t, ChartMath.tickDecimals(axis.step)), labelStyle, size.width - 4.dp.toPx(), y, hAlign = 1f)
                    }
                }
                val contentDp = with(density) { layout.contentWidth.toDp() }
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Canvas(
                        Modifier.width(maxOf(contentDp, 1.dp)).height(totalHeight).pointerInput(layout, bars) {
                            detectTapGestures { pos ->
                                val hit = layout.hit(pos.x)
                                selected = if (hit == selected) null else hit
                            }
                        },
                    ) {
                        val top = topPad.toPx()
                        val h = plotHeight.toPx()
                        val bottom = top + h
                        for (t in axis.ticks) {
                            val y = bottom - h * axis.fraction(t).toFloat()
                            drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                        val pitch = layout.barWidth + layout.gap
                        val labelStep = if (layout.count == 0) 1 else max(1, ceil(22.dp.toPx() / pitch).toInt())
                        for (i in 0 until layout.count) {
                            val slot = layout.slot(i)
                            val bar = bars[i]
                            val counts = bar.counts
                            if (counts == null) {
                                val hh = h * 0.28f
                                drawHatch(Offset(slot.left, bottom - hh), Size(slot.width, hh), colors.noData)
                                drawLabel(measurer, DASH, totalStyle, slot.center, bottom - hh - 2.dp.toPx(), vAlign = 1f)
                            } else {
                                var acc = 0f
                                for (c in counts.indices) {
                                    if (counts[c] <= 0) continue
                                    val segH = (counts[c] / axis.max).toFloat() * h
                                    drawRect(maturityColor(c), Offset(slot.left, bottom - acc - segH), Size(slot.width, segH))
                                    // Thin separator keeps adjacent segments (and dark colours on a dark theme) distinguishable.
                                    drawRect(surface, Offset(slot.left, bottom - acc - segH), Size(slot.width, segH), style = Stroke(0.6.dp.toPx()))
                                    acc += segH
                                }
                                if (acc > 0f) drawRect(colors.outline, Offset(slot.left, bottom - acc), Size(slot.width, acc), style = Stroke(0.8.dp.toPx()))
                                if (slot.width >= 22.dp.toPx()) {
                                    drawLabel(measurer, counts.sum().toString(), totalStyle, slot.center, bottom - acc - 2.dp.toPx(), vAlign = 1f)
                                }
                            }
                            if (i % labelStep == 0) drawLabel(measurer, bar.label, labelStyle, slot.center, bottom + 4.dp.toPx(), vAlign = 0f)
                            if (selected == i) {
                                drawRect(
                                    colors.ink,
                                    Offset(slot.left - 2.dp.toPx(), top - 2.dp.toPx()),
                                    Size(slot.width + 4.dp.toPx(), h + 4.dp.toPx()),
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                        }
                        drawLine(colors.outline, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
                    }
                }
            }
        }
        val sel = selected
        if (sel != null && sel in bars.indices) {
            Text(bars[sel].detail, style = MaterialTheme.typography.bodyMedium)
        } else if (showHint) {
            Text(
                stringResource(R.string.chart_tap_bar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
