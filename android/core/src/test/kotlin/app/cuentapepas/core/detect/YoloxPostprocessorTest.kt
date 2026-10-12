package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class YoloxPostprocessorTest {
    private fun raw(vararg rows: FloatArray) = rows.flatMap { it.toList() }.toFloatArray()
    private fun row(cx: Float, cy: Float, w: Float, h: Float, obj: Float, vararg cls: Float) = floatArrayOf(cx, cy, w, h, obj, *cls)
    private val pp = YoloxPostprocessor(numClasses = 5)

    @Test fun thresholdUsesObjTimesClsAndArgmax() {
        val out = raw(row(100f, 100f, 20f, 20f, 0.9f, 0.1f, 0.1f, 0.8f, 0f, 0f), row(300f, 100f, 20f, 20f, 0.3f, 0.9f, 0f, 0f, 0f, 0f))
        val d = pp.process(out, numAnchors = 2, transposed = false, scoreThreshold = 0.5f, nmsIou = 0.45f, maxDetections = 300)
        assertEquals(1, d.size); assertEquals(2, d[0].cls); assertEquals(0.72f, d[0].score, 1e-6f)
        assertEquals(Box(90f, 90f, 110f, 110f), d[0].box)
    }

    @Test fun nmsIsClassAgnostic() {
        val out = raw(row(100f, 100f, 20f, 20f, 0.9f, 0.9f, 0f, 0f, 0f, 0f), row(102f, 100f, 20f, 20f, 0.9f, 0f, 0f, 0.8f, 0f, 0f))
        val d = pp.process(out, 2, false, 0.1f, 0.45f, 300)
        assertEquals(1, d.size); assertEquals(0, d[0].cls)
    }

    @Test fun transposedLayoutGivesSameResult() {
        val a = row(100f, 100f, 20f, 20f, 0.9f, 0.1f, 0.1f, 0.8f, 0f, 0f); val b = row(300f, 100f, 20f, 20f, 0.9f, 0.9f, 0f, 0f, 0f, 0f)
        val normal = pp.process(raw(a, b), 2, false, 0.3f, 0.45f, 300)
        val t = FloatArray(20); for (i in 0 until 2) for (j in 0 until 10) t[j * 2 + i] = (if (i == 0) a else b)[j]
        assertEquals(normal, pp.process(t, 2, true, 0.3f, 0.45f, 300))
    }

    @Test fun classProbsKeptForMaturityVote() {
        val d = pp.process(raw(row(50f, 50f, 10f, 10f, 1f, 0.2f, 0.6f, 0.2f, 0f, 0f)), 1, false, 0.1f, 0.45f, 300)[0]
        assertArrayEquals(floatArrayOf(0.2f, 0.6f, 0.2f, 0f, 0f), d.classProbs, 1e-6f)
    }

    @Test fun maxDetectionsCaps() {
        val rows = (0 until 10).map { row(it * 50f, 10f, 10f, 10f, 1f, 1f, 0f, 0f, 0f, 0f) }.toTypedArray()
        assertEquals(3, pp.process(raw(*rows), 10, false, 0.1f, 0.45f, 3).size)
    }

    @Test fun scoreExactlyAtThresholdIsKept() {
        // 0.5 * 0.5 = 0.25 exactly in float32; contract is score >= threshold.
        val d = pp.process(raw(row(50f, 50f, 10f, 10f, 0.5f, 0.5f, 0f, 0f, 0f, 0f)), 1, false, 0.25f, 0.45f, 300)
        assertEquals(1, d.size)
    }

    @Test fun nmsSuppressesOnlyWhenIouStrictlyGreaterThanThreshold() {
        // Two 10x10 boxes shifted by 5 in x: IoU = 50/150 = 1/3. A threshold equal to that IoU must NOT suppress.
        val out = raw(row(5f, 5f, 10f, 10f, 0.9f, 1f, 0f, 0f, 0f, 0f), row(10f, 5f, 10f, 10f, 0.8f, 1f, 0f, 0f, 0f, 0f))
        val iou = Box(0f, 0f, 10f, 10f).iou(Box(5f, 0f, 15f, 10f))
        assertEquals(2, pp.process(out, 2, false, 0.1f, iou, 300).size)
        assertEquals(1, pp.process(out, 2, false, 0.1f, iou - 1e-4f, 300).size)
    }

    @Test fun nmsKeepsHigherScoreEvenWhenItComesLater() {
        val out = raw(row(100f, 100f, 20f, 20f, 0.6f, 1f, 0f, 0f, 0f, 0f), row(101f, 100f, 20f, 20f, 0.9f, 0f, 1f, 0f, 0f, 0f))
        val d = pp.process(out, 2, false, 0.1f, 0.45f, 300)
        assertEquals(1, d.size); assertEquals(1, d[0].cls); assertEquals(0.9f, d[0].score, 1e-6f)
    }

    @Test fun tiesKeepIndexOrderAndOutputIsDescending() {
        val rows = arrayOf(
            row(10f, 10f, 4f, 4f, 0.5f, 1f, 0f, 0f, 0f, 0f),
            row(100f, 10f, 4f, 4f, 0.9f, 0f, 1f, 0f, 0f, 0f),
            row(200f, 10f, 4f, 4f, 0.5f, 0f, 0f, 1f, 0f, 0f),
            row(300f, 10f, 4f, 4f, 0.5f, 0f, 0f, 0f, 1f, 0f),
        )
        val d = pp.process(raw(*rows), 4, false, 0.1f, 0.45f, 300)
        assertEquals(listOf(1, 0, 2, 3), d.map { it.cls })
        assertEquals(d.map { it.score }, d.map { it.score }.sortedDescending())
    }

    @Test fun maxDetectionsKeepsTheBestOnes() {
        val rows = (0 until 6).map { row(it * 50f, 10f, 10f, 10f, 0.5f + it * 0.05f, 1f, 0f, 0f, 0f, 0f) }.toTypedArray()
        val d = pp.process(raw(*rows), 6, false, 0.1f, 0.45f, 2)
        assertEquals(listOf(5, 4), d.map { (it.box.cx / 50f).toInt() })
    }

    @Test fun emptyAndAllBelowThreshold() {
        assertEquals(0, pp.process(FloatArray(0), 0, false, 0.3f, 0.45f, 300).size)
        val out = raw(row(50f, 50f, 10f, 10f, 0.1f, 0.5f, 0f, 0f, 0f, 0f))
        assertEquals(0, pp.process(out, 1, false, 0.3f, 0.45f, 300).size)
    }

    @Test fun instanceIsReusableAcrossFramesWithDifferentSizes() {
        val many = (0 until 40).map { row(it * 30f, 10f, 10f, 10f, 1f, 1f, 0f, 0f, 0f, 0f) }.toTypedArray()
        assertEquals(40, pp.process(raw(*many), 40, false, 0.1f, 0.45f, 300).size)
        val one = raw(row(5f, 5f, 4f, 4f, 1f, 0f, 1f, 0f, 0f, 0f))
        val d = pp.process(one, 1, false, 0.1f, 0.45f, 300)
        assertEquals(1, d.size); assertEquals(1, d[0].cls)
    }

    @Test fun handlesTheAnchorCountsOfTheLargerVariants() {
        // Anchors = sum over strides 8/16/32 of (H/s)*(W/s): 544x960 -> 10710, 736x1280 -> 19320.
        for ((h, w, anchors) in listOf(Triple(544, 960, 10710), Triple(736, 1280, 19320))) {
            assertEquals(anchors, listOf(8, 16, 32).sumOf { (h / it) * (w / it) })
            val rows = FloatArray(anchors * 10)
            fun put(i: Int, cx: Float, cls: Int) {
                val r = floatArrayOf(cx, 50f, 20f, 20f, 0.9f, 0f, 0f, 0f, 0f, 0f)
                r[5 + cls] = 0.9f
                r.copyInto(rows, i * 10)
            }
            put(0, 10f, 0); put(anchors / 2, 300f, 2); put(anchors - 1, 700f, 4)
            val d = pp.process(rows, anchors, false, 0.3f, 0.45f, 300)
            assertEquals(listOf(0, 2, 4), d.map { it.cls })
            assertEquals(3, d.size)

            // Same data, transposed layout.
            val t = FloatArray(rows.size)
            for (a in 0 until anchors) for (c in 0 until 10) t[c * anchors + a] = rows[a * 10 + c]
            assertEquals(3, pp.process(t, anchors, true, 0.3f, 0.45f, 300).size)
        }
    }

    @Test fun returnedDetectionsDoNotAliasInternalBuffers() {
        val first = pp.process(raw(row(50f, 50f, 10f, 10f, 1f, 0.2f, 0.6f, 0.2f, 0f, 0f)), 1, false, 0.1f, 0.45f, 300)
        pp.process(raw(row(80f, 80f, 10f, 10f, 1f, 0f, 0f, 0f, 1f, 0f)), 1, false, 0.1f, 0.45f, 300)
        assertArrayEquals(floatArrayOf(0.2f, 0.6f, 0.2f, 0f, 0f), first[0].classProbs, 1e-6f)
    }

    @Test fun negativeZeroScoreTiesWithPositiveZero() {
        // Python's stable argsort treats -0.0 and +0.0 as equal, so index order must win.
        val out = raw(row(10f, 10f, 4f, 4f, -0f, 1f, 0f, 0f, 0f, 0f), row(100f, 10f, 4f, 4f, 0f, 0f, 1f, 0f, 0f, 0f))
        val d = pp.process(out, 2, false, 0f, 0.45f, 300)
        assertEquals(listOf(0, 1), d.map { it.cls })
    }

    @Test fun manyEqualScoresKeepAnchorOrder() {
        val rows = (0 until 200).map { row(it * 20f, 10f, 4f, 4f, 0.5f, 1f, 0f, 0f, 0f, 0f) }.toTypedArray()
        val d = pp.process(raw(*rows), 200, false, 0.1f, 0.45f, 300)
        assertEquals((0 until 200).map { it * 20f }, d.map { it.box.cx })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawTooShortFails() {
        pp.process(FloatArray(9), 1, false, 0.3f, 0.45f, 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawWithWrongNumberOfChannelsFails() {
        pp.process(FloatArray(20), 1, false, 0.3f, 0.45f, 300)
    }

    /** Parity with Python reference (fixtures from Plan A Task A7); skipped until fixtures exist. */
    @Test fun matchesPythonFixtures() {
        val dir = javaClass.getResource("/fixtures")
        assumeTrue("fixtures not generated yet", dir != null)
        val folder = java.io.File(dir!!.toURI())
        val raws = folder.listFiles { f -> f.name.startsWith("raw_") }.orEmpty()
        assumeTrue("no raw_*.bin fixtures", raws.isNotEmpty())
        for (rawFile in raws) {
            val k = rawFile.name.removePrefix("raw_").removeSuffix(".bin")
            val buf = java.nio.ByteBuffer.wrap(rawFile.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(buf.remaining()).also { buf.get(it) }
            val exp = Json.parseToJsonElement(java.io.File(folder, "expected_$k.json").readText()).jsonObject
            val dets = pp.process(arr, arr.size / 10, false, exp["score_threshold"]!!.jsonPrimitive.float, exp["nms_iou"]!!.jsonPrimitive.float, 300)
            val expected = exp["detections"]!!.jsonArray
            assertEquals("count for $k", expected.size, dets.size)
            expected.forEachIndexed { i, e ->
                val box = e.jsonObject["box"]!!.jsonArray.map { it.jsonPrimitive.float }
                assertEquals("cls $k[$i]", e.jsonObject["cls"]!!.jsonPrimitive.int, dets[i].cls)
                assertEquals("score $k[$i]", e.jsonObject["score"]!!.jsonPrimitive.float, dets[i].score, 1e-4f)
                assertEquals("x1 $k[$i]", box[0], dets[i].box.x1, 1e-3f)
                assertEquals("y1 $k[$i]", box[1], dets[i].box.y1, 1e-3f)
                assertEquals("x2 $k[$i]", box[2], dets[i].box.x2, 1e-3f)
                assertEquals("y2 $k[$i]", box[3], dets[i].box.y2, 1e-3f)
            }
        }
    }
}
