package app.cuentapepas.core.chart

import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.core.stats.Interval

/**
 * Checks done before a chart is drawn: a value that cannot be placed on an axis (non-finite, or so large that the axis
 * span overflows) must show "—" and a note, never throw inside the draw code.
 */
object ChartGuard {
    private fun ok(v: Double) = v.isFinite()

    /** True when [e] (value and both interval bounds) can be drawn on an axis. */
    fun drawable(e: Estimate): Boolean = drawable(e.value, e.ci.low, e.ci.high)

    fun drawable(value: Double, low: Double, high: Double): Boolean {
        if (!ok(value) || !ok(low) || !ok(high)) return false
        val lo = minOf(value, low, high, 0.0)
        val hi = maxOf(value, low, high, 0.0)
        return ok(hi - lo)
    }

    /** True when the fitted line y = k·x and its band [kCi] can be drawn. */
    fun drawableFit(k: Double, kCi: Interval?): Boolean =
        ok(k) && k >= 0.0 && (kCi == null || (ok(kCi.low) && ok(kCi.high) && kCi.low >= 0.0 && ok(kCi.high - kCi.low)))

    /** True when every share can be drawn on the shared 0..1 axis. */
    fun drawableShares(shares: List<Estimate>): Boolean = shares.all { drawable(it) }
}
