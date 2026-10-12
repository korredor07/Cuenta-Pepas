package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxTest {
    @Test fun frame1280x720Into640x384() {
        val lb = Letterbox.fit(srcWidth = 1280, srcHeight = 720, dstWidth = 640, dstHeight = 384)
        assertEquals(0.5f, lb.scale, 1e-6f); assertEquals(640, lb.resizedWidth); assertEquals(360, lb.resizedHeight)
        assertEquals(Box(200f, 100f, 400f, 300f), lb.toSource(Box(100f, 50f, 200f, 150f)))
    }

    @Test fun toSourceClipsToFrame() {
        val lb = Letterbox.fit(1280, 720, 640, 384)
        assertEquals(Box(0f, 600f, 1280f, 720f), lb.toSource(Box(-10f, 300f, 700f, 380f)))
    }

    @Test fun toModelIsInverseOfToSourceInsideFrame() {
        val lb = Letterbox.fit(1280, 720, 640, 384)
        val src = Box(200f, 100f, 400f, 300f)
        assertEquals(Box(100f, 50f, 200f, 150f), lb.toModel(src))
        assertEquals(src, lb.toSource(lb.toModel(src)))
    }

    @Test fun portraitFrameIsLimitedByHeight() {
        val lb = Letterbox.fit(srcWidth = 720, srcHeight = 1280, dstWidth = 640, dstHeight = 384)
        assertEquals(0.3f, lb.scale, 1e-6f); assertEquals(216, lb.resizedWidth); assertEquals(384, lb.resizedHeight)
    }

    @Test fun resizedSizeNeverExceedsDestination() {
        val lb = Letterbox.fit(1919, 1081, 640, 384)
        assertTrue("${lb.resizedWidth}x${lb.resizedHeight}", lb.resizedWidth <= 640 && lb.resizedHeight <= 384)
    }

    @Test fun frame1280x720Into960x544HasScale075AndFourPadRows() {
        val lb = Letterbox.fit(srcWidth = 1280, srcHeight = 720, dstWidth = 960, dstHeight = 544)
        assertEquals(0.75f, lb.scale, 1e-6f); assertEquals(960, lb.resizedWidth); assertEquals(540, lb.resizedHeight)
        assertEquals(544 - 540, 544 - lb.resizedHeight)
        assertEquals(Box(400f, 200f, 800f, 400f), lb.toSource(Box(300f, 150f, 600f, 300f)))
    }

    @Test fun frame1280x720Into1280x736HasScale1AndSixteenPadRows() {
        val lb = Letterbox.fit(srcWidth = 1280, srcHeight = 720, dstWidth = 1280, dstHeight = 736)
        assertEquals(1.0f, lb.scale, 0f); assertEquals(1280, lb.resizedWidth); assertEquals(720, lb.resizedHeight)
        assertEquals(16, 736 - lb.resizedHeight)
        val b = Box(10f, 20f, 110f, 220f)
        assertEquals(b, lb.toSource(b))
        assertEquals(b, lb.toModel(b))
    }

    @Test fun portraitFrameIsLimitedByHeightAtEveryVariant() {
        val a = Letterbox.fit(720, 1280, 960, 544)
        assertEquals(544f / 1280f, a.scale, 1e-6f); assertEquals(306, a.resizedWidth); assertEquals(544, a.resizedHeight)
        val b = Letterbox.fit(720, 1280, 1280, 736)
        assertEquals(736f / 1280f, b.scale, 1e-6f); assertEquals(414, b.resizedWidth); assertEquals(736, b.resizedHeight)
    }
}
