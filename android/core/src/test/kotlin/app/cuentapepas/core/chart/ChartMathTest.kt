package app.cuentapepas.core.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChartMathTest {
    private val eps = 1e-9

    @Test fun niceAxisCoversDataWithRoundSteps() {
        val a = ChartMath.niceAxis(0.0, 87.0)
        assertEquals(0.0, a.min, eps)
        assertEquals(100.0, a.max, eps)
        assertEquals(20.0, a.step, eps)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), a.ticks)
        val b = ChartMath.niceAxis(0.0, 1.0)
        assertEquals(0.0, b.min, eps)
        assertEquals(1.0, b.max, eps)
        assertEquals(0.2, b.step, eps)
        assertEquals(6, b.ticks.size)
    }

    @Test fun niceAxisAlwaysContainsTheData() {
        val cases = listOf(0.0 to 3.0, 0.0 to 7.0, 1.3 to 9.9, 12.0 to 4800.0, 0.0 to 0.037, -5.0 to 12.0, 0.0 to 100000.0)
        for ((lo, hi) in cases) {
            val a = ChartMath.niceAxis(lo, hi)
            assertTrue("axis $a must contain $lo..$hi", a.min <= lo + eps && a.max >= hi - eps)
            assertTrue(a.ticks.size in 2..8)
            for (i in 1 until a.ticks.size) assertEquals(a.step, a.ticks[i] - a.ticks[i - 1], a.step * 1e-6)
        }
    }

    @Test fun niceAxisFlatRangeIsNeverDegenerate() {
        val zero = ChartMath.niceAxis(0.0, 0.0)
        assertEquals(0.0, zero.min, eps)
        assertEquals(1.0, zero.max, eps)
        val flat = ChartMath.niceAxis(50.0, 50.0)
        assertTrue(flat.span > 0.0)
        assertTrue(flat.min <= 50.0 && flat.max >= 50.0)
    }

    @Test fun niceAxisRejectsBadInput() {
        for (bad in listOf({ ChartMath.niceAxis(Double.NaN, 1.0) }, { ChartMath.niceAxis(2.0, 1.0) }, { ChartMath.niceAxis(0.0, 1.0, 1) })) {
            try {
                bad()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun tickDecimalsMatchTheStep() {
        assertEquals(0, ChartMath.tickDecimals(20.0))
        assertEquals(0, ChartMath.tickDecimals(1.0))
        assertEquals(1, ChartMath.tickDecimals(0.5))
        assertEquals(1, ChartMath.tickDecimals(0.2))
        assertEquals(2, ChartMath.tickDecimals(0.05))
        assertEquals(2, ChartMath.tickDecimals(0.01))
        try {
            ChartMath.tickDecimals(0.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun zeroBasedAxisStartsAtZero() {
        val a = ChartMath.zeroBasedAxis(42.0)
        assertEquals(0.0, a.min, eps)
        assertTrue(a.max >= 42.0)
        assertEquals(1.0, ChartMath.zeroBasedAxis(0.0).max, eps)
        assertEquals(0.5, a.fraction(a.max / 2), eps)
    }

    @Test fun estimateDomainIncludesValueBoundsAndZero() {
        val d = ChartMath.estimateDomain(120.0, 100.0, 140.0)
        assertEquals(0.0, d.min, eps)
        assertTrue(d.max >= 140.0)
        // The point estimate can fall outside a bootstrap interval: the domain must still show it.
        val out = ChartMath.estimateDomain(500.0, 100.0, 140.0)
        assertTrue(out.max >= 500.0)
        val tight = ChartMath.estimateDomain(1020.0, 1000.0, 1040.0, includeZero = false)
        assertTrue(tight.min > 0.0 && tight.min <= 1000.0 && tight.max >= 1040.0)
    }

    @Test fun clampIntervalKeepsBoundsInsideTheDomain() {
        assertEquals(0.0 to 1.0, ChartMath.clampInterval(-0.2, 1.3, 0.0, 1.0))
        assertEquals(0.2 to 0.4, ChartMath.clampInterval(0.4, 0.2, 0.0, 1.0))
        assertEquals(1.0 to 1.0, ChartMath.clampInterval(1.5, 2.0, 0.0, 1.0))
    }

    @Test fun barLayoutFitsWhenFewBars() {
        val l = ChartMath.barLayout(5, 300f, minBar = 10f, maxBar = 40f)
        assertFalse(l.scrolls)
        assertEquals(40f, l.barWidth, 1e-4f)
        assertTrue(l.contentWidth <= 300f)
        val s = l.slot(0)
        assertEquals(l.gap / 2f, s.left, 1e-4f)
        assertEquals(l.barWidth, s.width, 1e-4f)
    }

    @Test fun barLayoutScrollsWhenBarsDoNotFit() {
        val l = ChartMath.barLayout(40, 320f, minBar = 14f, maxBar = 36f)
        assertTrue(l.scrolls)
        assertEquals(14f, l.barWidth, 1e-4f)
        assertTrue(l.contentWidth > 320f)
        val last = l.slot(39)
        assertTrue(last.left + last.width <= l.contentWidth)
    }

    @Test fun barLayoutFillsTheWidthWhenBarsFitBetweenLimits() {
        val l = ChartMath.barLayout(10, 300f, minBar = 8f, maxBar = 40f)
        assertFalse(l.scrolls)
        assertEquals(300f, l.contentWidth, 0.01f)
    }

    @Test fun barLayoutHitTest() {
        val l = ChartMath.barLayout(4, 200f, minBar = 10f, maxBar = 100f)
        for (i in 0 until 4) assertEquals(i, l.hit(l.slot(i).center))
        assertNull(l.hit(-1f))
        assertNull(l.hit(l.contentWidth + 1f))
        assertEquals(3, l.hit(l.contentWidth - 0.01f))
        assertNull(ChartMath.barLayout(0, 200f, 10f, 20f).hit(5f))
    }

    @Test fun barLayoutSlotOutOfRangeThrows() {
        try {
            ChartMath.barLayout(2, 100f, 10f, 20f).slot(2)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun fractionsAddToOneAndZeroTotalIsNull() {
        val f = ChartMath.fractions(intArrayOf(2, 6, 2, 0, 0))!!
        assertEquals(1.0, f.sum(), eps)
        assertEquals(0.6, f[1], eps)
        assertNull(ChartMath.fractions(intArrayOf(0, 0, 0)))
        assertNull(ChartMath.fractions(intArrayOf()))
    }

    @Test fun segmentsTileTheBar() {
        val seg = ChartMath.segments(doubleArrayOf(0.25, 0.25, 0.5))
        assertEquals(0.0 to 0.25, seg[0])
        assertEquals(0.25 to 0.5, seg[1])
        assertEquals(0.5 to 1.0, seg[2])
    }

    @Test fun roundedPercentsAddUpTo100() {
        val p = ChartMath.roundedPercents(doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3))
        assertEquals(100, p.sum())
        assertEquals(100, ChartMath.roundedPercents(doubleArrayOf(0.126, 0.374, 0.5)).sum())
        assertEquals(50, ChartMath.roundedPercents(doubleArrayOf(0.5, 0.5, 0.0))[0])
    }

    @Test fun nearestPointWithinRadius() {
        val xs = floatArrayOf(10f, 50f, 90f)
        val ys = floatArrayOf(10f, 50f, 90f)
        assertEquals(1, ChartMath.nearest(xs, ys, 52f, 47f, 10f))
        assertNull(ChartMath.nearest(xs, ys, 70f, 30f, 10f))
        assertNull(ChartMath.nearest(floatArrayOf(), floatArrayOf(), 0f, 0f, 10f))
    }

    @Test fun rayLeavesThroughRightOrTopEdge() {
        assertEquals(100.0 to 50.0, ChartMath.rayEnd(0.5, 100.0, 80.0))
        assertEquals(40.0 to 80.0, ChartMath.rayEnd(2.0, 100.0, 80.0))
        assertEquals(100.0 to 0.0, ChartMath.rayEnd(0.0, 100.0, 80.0))
    }

    @Test fun bandPolygonBetweenTwoLines() {
        val p = ChartMath.bandPolygon(1.0, 2.0, 100.0, 300.0)
        assertEquals(listOf(0.0 to 0.0, 100.0 to 100.0, 100.0 to 200.0), p)
    }

    @Test fun bandPolygonClippedByTheTopEdgeKeepsTheCorner() {
        val p = ChartMath.bandPolygon(0.5, 4.0, 100.0, 100.0)
        assertEquals(0.0 to 0.0, p.first())
        assertTrue(100.0 to 100.0 in p)
        assertNotNull(p.find { it.first == 25.0 && it.second == 100.0 })
        // Every vertex stays inside the box.
        for ((x, y) in p) assertTrue(x in 0.0..100.0 && y in 0.0..100.0)
    }

    @Test fun bandPolygonEntirelyAboveTheBoxLeavesATriangleAtTheTop() {
        val p = ChartMath.bandPolygon(2.0, 3.0, 100.0, 100.0)
        for ((x, y) in p) assertTrue(x in 0.0..100.0 && y in 0.0..100.0 + eps)
        assertEquals(3, p.size)
    }

    @Test fun bandPolygonRejectsInvertedBand() {
        try {
            ChartMath.bandPolygon(2.0, 1.0, 10.0, 10.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
