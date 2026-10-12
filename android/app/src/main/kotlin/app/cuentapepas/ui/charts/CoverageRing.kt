package app.cuentapepas.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.ui.theme.numberStyle
import app.cuentapepas.core.format.Fmt

/**
 * Ring with "X de N plantas". When the lot total is unknown there is no coverage: the ring is dashed grey and shows "—"
 * plus the visited count, never a 0 % ring.
 */
@Composable
fun CoverageRing(visited: Int, total: Int?, coverage: Double?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val colors = chartColors()
    val known = total != null && coverage != null
    val desc = stringResource(R.string.chart_coverage_desc, Fmt.coverage(visited, total, coverage))
    Row(modifier.semantics { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                if (known) {
                    drawArc(colors.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    val sweep = 360f * coverage!!.toFloat().coerceIn(0f, 1f)
                    if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                } else {
                    drawArc(
                        colors.noData, 0f, 360f, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke / 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                    )
                }
            }
            Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                if (known) {
                    Text(stringResource(R.string.chart_coverage_center, visited, total!!), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.chart_coverage_unit), style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(DASH, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.chart_coverage_unit), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Column(Modifier.clearAndSetSemantics { }) {
            Text(Fmt.coverage(visited, total, coverage), style = numberStyle)
            if (!known) Text(stringResource(R.string.chart_coverage_visited_only, visited), style = MaterialTheme.typography.bodySmall)
        }
    }
}
