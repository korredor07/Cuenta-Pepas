package app.cuentapepas.core.chart

import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.core.stats.Interval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartGuardTest {
    @Test fun normalEstimateIsDrawable() {
        assertTrue(ChartGuard.drawable(Estimate(10.0, Interval(8.0, 12.0))))
        assertTrue(ChartGuard.drawable(5.0, 5.0, 5.0))
    }

    @Test fun nonFiniteValuesAreNotDrawable() {
        assertFalse(ChartGuard.drawable(Double.NaN, 1.0, 2.0))
        assertFalse(ChartGuard.drawable(1.0, Double.NEGATIVE_INFINITY, 2.0))
        assertFalse(ChartGuard.drawable(1.0, 0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun spanOverflowIsNotDrawable() {
        assertFalse(ChartGuard.drawable(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE))
    }

    @Test fun fitGuard() {
        assertTrue(ChartGuard.drawableFit(1.2, Interval(1.0, 1.5)))
        assertTrue(ChartGuard.drawableFit(1.2, null))
        assertFalse(ChartGuard.drawableFit(Double.NaN, null))
        assertFalse(ChartGuard.drawableFit(-1.0, null))
        assertFalse(ChartGuard.drawableFit(1.0, Interval(-0.5, 1.0)))
    }

    @Test fun sharesGuard() {
        assertTrue(ChartGuard.drawableShares(listOf(Estimate(0.2, Interval(0.1, 0.3)))))
        assertFalse(ChartGuard.drawableShares(listOf(Estimate(0.2, Interval(0.1, 0.3)), Estimate(Double.MAX_VALUE, Interval(-Double.MAX_VALUE, Double.MAX_VALUE)))))
    }
}
