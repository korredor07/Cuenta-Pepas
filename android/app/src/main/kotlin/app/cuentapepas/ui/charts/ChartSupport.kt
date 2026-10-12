package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.ui.live.classColor

/** Colour of maturity class [index]: the same palette as the live detection overlay (verde, pintón, maduro, sobremaduro, seco). */
internal fun maturityColor(index: Int): Color = classColor(index)

/** Black or white, whichever reads better on [bg]. */
internal fun onColor(bg: Color): Color = if (bg.luminance() > 0.35f) Color.Black else Color.White

/** Neutral colours of the charts, derived from the Material scheme so they follow light and dark themes. */
internal class ChartColors(
    val grid: Color,
    val axisText: Color,
    val outline: Color,
    val track: Color,
    val noData: Color,
    val ink: Color,
)

@Composable
internal fun chartColors(): ChartColors {
    val s = MaterialTheme.colorScheme
    return ChartColors(
        grid = s.onSurface.copy(alpha = 0.14f),
        axisText = s.onSurfaceVariant,
        outline = s.onSurface.copy(alpha = 0.55f),
        track = s.surfaceVariant,
        noData = s.outline,
        ink = s.onSurface,
    )
}

@Composable
internal fun axisTextStyle(color: Color): TextStyle = MaterialTheme.typography.labelSmall.copy(color = color)

/** Draws [text] anchored at ([x], [y]): [hAlign] 0 = text starts at x, 0.5 = centred, 1 = ends at x; [vAlign] likewise for y. */
internal fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    hAlign: Float = 0.5f,
    vAlign: Float = 0.5f,
) {
    val layout = measurer.measure(text, style, maxLines = 1)
    drawText(layout, topLeft = Offset(x - layout.size.width * hAlign, y - layout.size.height * vAlign))
}

/** Diagonal hatch over a rectangle: the visual language of "sin dato" (never a zero-length bar). */
internal fun DrawScope.drawHatch(topLeft: Offset, size: Size, color: Color, spacing: Float = 6.dp.toPx(), stroke: Float = 1.2.dp.toPx()) {
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        drawRect(color.copy(alpha = 0.10f), topLeft, size)
        var x = topLeft.x - size.height
        while (x < topLeft.x + size.width) {
            drawLine(color, Offset(x, topLeft.y + size.height), Offset(x + size.height, topLeft.y), strokeWidth = stroke)
            x += spacing
        }
        drawRect(color, topLeft, size, style = Stroke(1.dp.toPx()))
    }
}

/** A small coloured square for legends. [hatched] draws the "sin dato" pattern instead of a flat colour. */
@Composable
internal fun Swatch(color: Color, hatched: Boolean = false) {
    val outline = chartColors().outline
    Canvas(Modifier.size(12.dp)) {
        if (hatched) {
            drawHatch(Offset.Zero, size, color, spacing = 4.dp.toPx(), stroke = 1.dp.toPx())
        } else {
            drawRect(color)
            drawRect(outline, style = Stroke(1.dp.toPx()))
        }
    }
}

/** Legend of the maturity classes, with an extra "Sin dato" entry when [showNoData]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaturityLegend(classNames: List<String>, modifier: Modifier = Modifier, showNoData: Boolean = false) {
    val colors = chartColors()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        classNames.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(maturityColor(i))
                Spacer(Modifier.width(5.dp))
                Text(name, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (showNoData) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(colors.noData, hatched = true)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.chart_legend_no_data), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "Ver tabla" toggle: the exact numbers behind a chart, collapsed unless [initiallyOpen]. */
@Composable
fun TableToggle(modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(if (open) R.string.chart_table_hide else R.string.chart_table_show))
        }
        if (open) content()
    }
}

/** Rounded track used behind horizontal bars. */
internal fun Modifier.trackBackground(color: Color): Modifier = this.clip(RoundedCornerShape(4.dp)).background(color)

/** Shown in place of a value that does not exist ("sin dato"); never a 0. */
internal const val DASH = "—"
