package app.cuentapepas.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPackerTest {

    @Test
    fun redPixelBecomesBgrWithBlueAndGreenZero() {
        val out = FloatArray(InputPacker.outputSize(1, 1))
        InputPacker.pack(intArrayOf(0xFFFF0000.toInt()), 1, 1, 1, 1, out)
        assertEquals(listOf(0f, 0f, 255f), out.toList())
    }

    @Test
    fun channelsAreStoredBlueGreenRedInNhwcOrder() {
        // 2x1 image: pixel 0 R=10 G=20 B=30; pixel 1 R=40 G=50 B=60 (alpha 0x80 must be ignored).
        val argb = intArrayOf(0xFF0A141E.toInt(), 0x8028323C.toInt())
        val out = FloatArray(6)
        InputPacker.pack(argb, 2, 1, 2, 1, out)
        assertEquals(listOf(30f, 20f, 10f, 60f, 50f, 40f), out.toList())
    }

    @Test
    fun rightAndBottomPaddingIs114() {
        // 2x2 image into 4x3: columns 2-3 and row 2 are padding.
        val green = 0xFF00FF00.toInt()
        val out = FloatArray(InputPacker.outputSize(4, 3)) { -1f }
        InputPacker.pack(IntArray(4) { green }, 2, 2, 4, 3, out)
        for (y in 0 until 3) for (x in 0 until 4) {
            val px = (y * 4 + x) * 3
            val inside = x < 2 && y < 2
            val expected = if (inside) listOf(0f, 255f, 0f) else listOf(114f, 114f, 114f)
            assertEquals("pixel ($x,$y)", expected, out.slice(px until px + 3))
        }
    }

    @Test
    fun customPadValueAndOversizedOutputArrayOnlyWritesTheTensor() {
        val out = FloatArray(InputPacker.outputSize(2, 1) + 5) { -7f }
        InputPacker.pack(intArrayOf(0xFFFFFFFF.toInt()), 1, 1, 2, 1, out, padValue = 0f)
        assertEquals(listOf(255f, 255f, 255f, 0f, 0f, 0f), out.slice(0 until 6))
        assertEquals(List(5) { -7f }, out.slice(6 until 11))
    }

    @Test
    fun exactFitHasNoPadding() {
        val out = FloatArray(InputPacker.outputSize(2, 2))
        InputPacker.pack(IntArray(4) { 0xFF000000.toInt() }, 2, 2, 2, 2, out)
        assertEquals(List(12) { 0f }, out.toList())
    }

    @Test
    fun rejectsImagesThatDoNotFitOrBuffersThatAreTooSmall() {
        val out = FloatArray(InputPacker.outputSize(2, 2))
        assertThrows(IllegalArgumentException::class.java) { InputPacker.pack(IntArray(9), 3, 3, 2, 2, out) }
        assertThrows(IllegalArgumentException::class.java) { InputPacker.pack(IntArray(3), 2, 2, 2, 2, out) }
        assertThrows(IllegalArgumentException::class.java) { InputPacker.pack(IntArray(4), 2, 2, 2, 2, FloatArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { InputPacker.pack(IntArray(0), 0, 2, 2, 2, out) }
    }

    @Test
    fun fullSizeVariantInputsHaveTheRightSizeAndPadding() {
        // 1280x720 frame at 736x1280: no right padding, 16 padding rows. 960x540 at 544x960: 4 padding rows.
        for ((dstW, dstH, w, h) in listOf(listOf(1280, 736, 1280, 720), listOf(960, 544, 960, 540))) {
            val out = FloatArray(InputPacker.outputSize(dstW, dstH)) { -1f }
            InputPacker.pack(IntArray(w * h) { 0xFF0000FF.toInt() }, w, h, dstW, dstH, out)
            assertEquals(dstW * dstH * 3, InputPacker.outputSize(dstW, dstH))
            assertEquals(listOf(255f, 0f, 0f), out.slice(0 until 3)) // top-left pixel: blue -> B=255
            val lastImagePx = ((h - 1) * dstW + (w - 1)) * 3
            assertEquals(listOf(255f, 0f, 0f), out.slice(lastImagePx until lastImagePx + 3))
            val firstPadRow = h * dstW * 3
            assertEquals(listOf(114f, 114f, 114f), out.slice(firstPadRow until firstPadRow + 3))
            assertEquals(114f, out[out.size - 1], 0f)
            assertTrue(out.none { it == -1f })
        }
    }
}
