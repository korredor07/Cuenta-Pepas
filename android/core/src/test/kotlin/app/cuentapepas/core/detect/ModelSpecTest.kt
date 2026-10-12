package app.cuentapepas.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpecTest {
    private fun exampleJson() = javaClass.getResource("/model_example.json")!!.readText()

    @Test fun parsesPlanAExample() {
        val spec = ModelSpec.parse(exampleJson())
        assertEquals("cuentapepas-nano-v1", spec.modelId)
        assertEquals(1, spec.taxonomySchemaVersion)
        assertEquals(640, spec.input.width); assertEquals(384, spec.input.height)
        assertEquals(640, spec.inputWidth); assertEquals(384, spec.inputHeight)
        assertEquals("BGR", spec.input.channelOrder); assertEquals(114, spec.input.padValue)
        assertEquals(listOf("green", "semi_ripe", "ripe", "overripe", "dry"), spec.classes)
        assertEquals(5, spec.numClasses)
        assertEquals(listOf(1, 5040, 10), spec.output.shape)
        assertEquals(5040, spec.numAnchors)
        assertFalse(spec.output.transposed)
        assertFalse(spec.transposed)
        assertEquals(0.30f, spec.postprocess.defaultScoreThreshold, 1e-6f)
        assertEquals(0.30f, spec.defaultScoreThreshold, 1e-6f)
        assertEquals(0.45f, spec.postprocess.defaultNmsIou, 1e-6f)
        assertEquals(300, spec.postprocess.maxDetections)
        assertEquals("cuentapepas_nano_fp16.tflite", spec.files.fp16.name)
        assertEquals("cuentapepas_nano_fp32.tflite", spec.files.fp32.name)
    }

    @Test fun transposedOutputFlagIsRead() {
        val json = exampleJson()
            .replace("\"shape\": [1, 5040, 10]", "\"shape\": [1, 10, 5040], \"transposed\": true")
        assertTrue("test setup: replacement must apply", json.contains("\"transposed\": true"))
        val spec = ModelSpec.parse(json)
        assertTrue(spec.output.transposed)
        assertTrue(spec.transposed)
        assertEquals(5040, spec.numAnchors)
        assertEquals(5, spec.numClasses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outputChannelsMustMatchClassCount() {
        // 6 classes declared but the output still has 5 + 5 channels.
        ModelSpec.parse(exampleJson().replace("\"dry\"]", "\"dry\", \"extra\"]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transposedFlagMustMatchShape() {
        // shape says [1, 5040, 10] but transposed=true makes the channel dim 5040.
        ModelSpec.parse(exampleJson().replace("\"shape\": [1, 5040, 10]", "\"shape\": [1, 5040, 10], \"transposed\": true"))
    }

    @Test fun unknownKeysAreIgnored() {
        val json = exampleJson().replaceFirst("{", "{\"future_field\": {\"a\": 1},")
        assertEquals(5040, ModelSpec.parse(json).numAnchors)
    }

    @Test fun nullMetricDoesNotBreakLoading() {
        val json = exampleJson().replace("\"val_map50\": 0.0", "\"val_map50\": null")
        assertTrue("test setup: replacement must apply", json.contains("\"val_map50\": null"))
        val spec = ModelSpec.parse(json)
        assertTrue(spec.metrics.containsKey("val_map50"))
        assertEquals(null, spec.metrics["val_map50"])
        assertEquals(0.0, spec.metrics["val_map50_95"]!!, 0.0)
    }

    @Test fun omittedMetricsDefaultToEmpty() {
        val json = exampleJson().replace(", \"metrics\": {\"val_map50\": 0.0, \"val_map50_95\": 0.0}", "")
        assertTrue("test setup: replacement must apply", !json.contains("metrics"))
        assertTrue(ModelSpec.parse(json).metrics.isEmpty())
    }

    private fun assertRejected(from: String, to: String, messagePart: String) {
        val json = exampleJson().replace(from, to)
        assertTrue("test setup: replacement must apply", json != exampleJson())
        val e = assertThrows(IllegalArgumentException::class.java) { ModelSpec.parse(json) }
        assertTrue("message was: ${e.message}", e.message!!.contains(messagePart))
    }

    @Test fun rejectsWrongInputLayout() = assertRejected("\"layout\": \"NHWC\"", "\"layout\": \"NCHW\"", "input.layout")

    @Test fun rejectsWrongChannelOrder() = assertRejected("\"channel_order\": \"BGR\"", "\"channel_order\": \"RGB\"", "input.channel_order")

    @Test fun rejectsWrongLetterbox() = assertRejected("\"letterbox\": \"top_left\"", "\"letterbox\": \"center\"", "input.letterbox")

    @Test fun rejectsWrongCoords() = assertRejected("\"coords\": \"input_pixels\"", "\"coords\": \"normalized\"", "output.coords")

    @Test fun rejectsWrongActivations() =
        assertRejected("\"activations\": \"sigmoid_applied\"", "\"activations\": \"logits\"", "output.activations")

    @Test fun rejectsBatchOtherThanOne() = assertRejected("\"shape\": [1, 5040, 10]", "\"shape\": [2, 5040, 10]", "output.shape[0]")

    @Test fun variantFieldAndLargerInputsAreGeneric() {
        val json = exampleJson()
            .replaceFirst("{", "{\"variant\": \"r544x960\",")
            .replace("\"width\": 640, \"height\": 384", "\"width\": 960, \"height\": 544")
            .replace("\"shape\": [1, 5040, 10]", "\"shape\": [1, 10710, 10]")
        val spec = ModelSpec.parse(json)
        assertEquals("r544x960", spec.variant)
        assertEquals(960, spec.inputWidth); assertEquals(544, spec.inputHeight)
        assertEquals(10710, spec.numAnchors)
        assertEquals(null, ModelSpec.parse(exampleJson()).variant)
    }
}
