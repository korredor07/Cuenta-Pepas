package app.cuentapepas.core.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** An axis with "nice" bounds: [min] and [max] are multiples of [step] and [ticks] runs from [min] to [max] inclusive. */
data class NiceAxis(val min: Double, val max: Double, val step: Double, val ticks: List<Double>) {
    val span: Double get() = max - min

    /** Position of [v] along the axis as 0..1 (not clamped). */
    fun fraction(v: Double): Double = (v - min) / span
}

/** One bar slot of a [BarLayout], in pixels from the left edge of the chart content. */
class BarSlot(val left: Float, val width: Float) {
    val center: Float get() = left + width / 2f
}

/**
 * Layout of [count] bars. Bars are never narrower than the requested minimum: when they do not fit the available width
 * the content is wider ([scrolls]) and the chart is meant to be scrolled horizontally.
 */
class BarLayout internal constructor(
    val count: Int,
    val barWidth: Float,
    val gap: Float,
    val contentWidth: Float,
    val scrolls: Boolean,
) {
    private val pitch: Float get() = barWidth + gap

    /** Bars start half a gap from the edge so selection outlines are not clipped. */
    fun slot(i: Int): BarSlot {
        require(i in 0 until count) { "bar index out of range: $i of $count" }
        return BarSlot(gap / 2f + i * pitch, barWidth)
    }

    /** Index of the bar under [x] (the gap around a bar counts as part of it), or null outside every bar. */
    fun hit(x: Float): Int? {
        if (count == 0 || x < 0f || x >= contentWidth) return null
        return (x / pitch).toInt().coerceIn(0, count - 1)
    }
}

/** Pure geometry and scaling for the charts. No Compose and no Android: everything here is unit-tested. */
object ChartMath {
    /** Rounds [x] to 1, 2, 5 or 10 times a power of ten: nearest when [round], otherwise the next one up. */
    internal fun niceNumber(x: Double, round: Boolean): Double {
        val exp = floor(log10(x))
        val f = x / 10.0.pow(exp)
        val nf = if (round) {
            when {
                f < 1.5 -> 1.0
                f < 3.0 -> 2.0
                f < 7.0 -> 5.0
                else -> 10.0
            }
        } else {
            when {
                f <= 1.0 -> 1.0
                f <= 2.0 -> 2.0
                f <= 5.0 -> 5.0
                else -> 10.0
            }
        }
        return nf * 10.0.pow(exp)
    }

    /**
     * Axis covering [dataMin]..[dataMax] with about [maxTicks] ticks. The axis always contains the data. A flat range
     * (min == max) is widened so the axis is never degenerate; when both are 0 the axis is 0..1.
     */
    fun niceAxis(dataMin: Double, dataMax: Double, maxTicks: Int = 5): NiceAxis {
        require(dataMin.isFinite() && dataMax.isFinite()) { "axis bounds must be finite: $dataMin, $dataMax" }
        require(dataMin <= dataMax) { "axis bounds are inverted: $dataMin, $dataMax" }
        require(maxTicks >= 2) { "maxTicks must be >= 2" }
        var lo = dataMin
        var hi = dataMax
        if (lo == hi) {
            if (lo == 0.0) {
                hi = 1.0
            } else {
                val pad = abs(lo) * 0.1
                lo -= pad
                hi += pad
            }
        }
        val range = niceNumber(hi - lo, false)
        val step = niceNumber(range / (maxTicks - 1), true)
        val first = floor(lo / step + 1e-9).toLong()
        val last = ceil(hi / step - 1e-9).toLong()
        val ticks = (first..last).map { cleanZero(it * step) }
        return NiceAxis(ticks.first(), ticks.last(), step, ticks)
    }

    /** Decimals needed to print every tick of an axis with the given [step] without rounding it (0 for steps >= 1). */
    fun tickDecimals(step: Double): Int {
        require(step > 0.0 && step.isFinite()) { "step must be positive and finite: $step" }
        if (step >= 1.0) return 0
        return ceil(-log10(step) - 1e-9).toInt().coerceIn(0, 6)
    }

    /** Axis from 0 to the nice ceiling of [dataMax]; counts and ratios are anchored at zero so bar lengths stay honest. */
    fun zeroBasedAxis(dataMax: Double, maxTicks: Int = 5): NiceAxis = niceAxis(0.0, max(dataMax, 0.0), maxTicks)

    private fun cleanZero(v: Double): Double = if (v == 0.0) 0.0 else v

    /**
     * Horizontal domain for an estimate drawn with its interval. It contains the value and both bounds (a bootstrap
     * interval may not contain the point value, so the value is included explicitly) and, with [includeZero], also 0.
     */
    fun estimateDomain(value: Double, low: Double, high: Double, includeZero: Boolean = true, maxTicks: Int = 5): NiceAxis {
        require(value.isFinite() && low.isFinite() && high.isFinite()) { "estimate must be finite" }
        var lo = min(value, min(low, high))
        var hi = max(value, max(low, high))
        if (includeZero) {
            lo = min(lo, 0.0)
            hi = max(hi, 0.0)
        }
        return niceAxis(lo, hi, maxTicks)
    }

    /** Clamps an interval into [lo]..[hi]; the bounds are swapped when inverted. */
    fun clampInterval(low: Double, high: Double, lo: Double, hi: Double): Pair<Double, Double> {
        require(lo <= hi) { "clamp range is inverted: $lo, $hi" }
        val a = min(low, high).coerceIn(lo, hi)
        val b = max(low, high).coerceIn(lo, hi)
        return a to b
    }

    /**
     * Layout of [count] bars in [availableWidth]. Bars share the width evenly within [minBar]..[maxBar]; [gapRatio] is the
     * gap between bars as a fraction of the bar width.
     */
    fun barLayout(count: Int, availableWidth: Float, minBar: Float, maxBar: Float, gapRatio: Float = 0.25f): BarLayout {
        require(count >= 0) { "count must be >= 0" }
        require(availableWidth >= 0f && minBar > 0f && maxBar >= minBar && gapRatio >= 0f) { "invalid bar layout parameters" }
        if (count == 0) return BarLayout(0, 0f, 0f, 0f, false)
        val ideal = availableWidth / (count * (1f + gapRatio))
        val bar = ideal.coerceIn(minBar, maxBar)
        val gap = bar * gapRatio
        val content = count * (bar + gap)
        return BarLayout(count, bar, gap, content, content > availableWidth + 0.5f)
    }

    /** Fraction of each class in [counts] (adds up to 1), or null when the total is 0: there is no share, never a 0 % one. */
    fun fractions(counts: IntArray): DoubleArray? {
        val total = counts.sumOf { it.toLong() }
        if (total <= 0L) return null
        return DoubleArray(counts.size) { counts[it].toDouble() / total }
    }

    /** Cumulative start and end (0..1) of each segment of a 100 % bar. */
    fun segments(fractions: DoubleArray): List<Pair<Double, Double>> {
        var acc = 0.0
        return fractions.map { f ->
            val s = acc
            acc += f
            s to min(acc, 1.0)
        }
    }

    /** Whole percentages that add up to exactly 100 (largest remainder), for labels inside segments. */
    fun roundedPercents(fractions: DoubleArray): IntArray {
        val raw = DoubleArray(fractions.size) { fractions[it] * 100.0 }
        val out = IntArray(raw.size) { floor(raw[it] + 1e-9).toInt() }
        var missing = 100 - out.sum()
        val order = raw.indices.sortedByDescending { raw[it] - out[it] }
        var i = 0
        while (missing > 0 && order.isNotEmpty()) {
            out[order[i % order.size]]++
            missing--
            i++
        }
        return out
    }

    /** Index of the point nearest to ([x], [y]) within [maxDistance] (same unit for all arguments), or null. */
    fun nearest(xs: FloatArray, ys: FloatArray, x: Float, y: Float, maxDistance: Float): Int? {
        require(xs.size == ys.size) { "xs and ys differ in size" }
        var best: Int? = null
        var bestD = Double.MAX_VALUE
        for (i in xs.indices) {
            val dx = (xs[i] - x).toDouble()
            val dy = (ys[i] - y).toDouble()
            val d = sqrt(dx * dx + dy * dy)
            if (d <= maxDistance && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /**
     * End of the ray y = k·x from the origin inside the box 0..[xMax] x 0..[yMax]: it leaves through the right edge or
     * the top edge, whichever comes first. [k] must be >= 0.
     */
    fun rayEnd(k: Double, xMax: Double, yMax: Double): Pair<Double, Double> {
        require(k >= 0.0 && xMax > 0.0 && yMax > 0.0) { "invalid ray parameters" }
        return if (k * xMax <= yMax) xMax to k * xMax else (yMax / k) to yMax
    }

    /**
     * Polygon (data units, starting at the origin) of the band between the lines y = [kLow]·x and y = [kHigh]·x
     * inside the box 0..[xMax] x 0..[yMax].
     */
    fun bandPolygon(kLow: Double, kHigh: Double, xMax: Double, yMax: Double): List<Pair<Double, Double>> {
        require(kLow >= 0.0 && kLow <= kHigh) { "invalid band: $kLow, $kHigh" }
        val lowEnd = rayEnd(kLow, xMax, yMax)
        val highEnd = rayEnd(kHigh, xMax, yMax)
        val pts = ArrayList<Pair<Double, Double>>()
        pts += 0.0 to 0.0
        pts += lowEnd
        // The low line leaves through the right edge and the high line through the top: include the corner.
        if (lowEnd.first >= xMax && highEnd.second >= yMax && highEnd.first < xMax) pts += xMax to yMax
        if (highEnd != lowEnd) pts += highEnd
        return pts
    }
}
