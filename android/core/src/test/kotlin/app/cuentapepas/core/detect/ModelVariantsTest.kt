package app.cuentapepas.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVariantsTest {
    private val contract = """
        {"default":"r544x960","variants":[
          {"id":"r384x640","label_es":"Normal (rápido)","input":[384,640]},
          {"id":"r544x960","label_es":"Alta","input":[544,960]},
          {"id":"r736x1280","label_es":"Máxima (más lento)","input":[736,1280]}]}
    """.trimIndent()

    private fun spec(variant: String?, w: Int, h: Int, anchors: Int): ModelSpec {
        val v = if (variant != null) "\"variant\": \"$variant\"," else ""
        return ModelSpec.parse(
            """{$v "model_id":"m","taxonomy_schema_version":1,"classes":["a","b","c","d","e"],
            "input":{"name":"images","width":$w,"height":$h,"layout":"NHWC","dtype":"float32","channel_order":"BGR",
              "range":[0,255],"letterbox":"top_left","pad_value":114},
            "output":{"shape":[1,$anchors,10],"layout":[],"coords":"input_pixels","activations":"sigmoid_applied"},
            "postprocess":{"score":"s","default_score_threshold":0.3,"nms":"n","default_nms_iou":0.45,"max_detections":300},
            "files":{"fp16":{"name":"a","sha256":"x"},"fp32":{"name":"b","sha256":"y"}}}""",
        )
    }

    @Test fun parsesTheContract() {
        val v = ModelVariants.parse(contract)
        assertEquals("r544x960", v.default)
        assertEquals(listOf("r384x640", "r544x960", "r736x1280"), v.variants.map { it.id })
        assertEquals("Máxima (más lento)", v.byId("r736x1280")!!.labelEs)
        assertEquals(736, v.byId("r736x1280")!!.inputHeight)
        assertEquals(1280, v.byId("r736x1280")!!.inputWidth)
        assertEquals("r544x960", v.defaultVariant.id)
        assertNull(v.byId("nope"))
    }

    @Test fun unknownKeysAreIgnored() {
        val v = ModelVariants.parse(contract.replaceFirst("{", "{\"future\": 1,"))
        assertEquals(3, v.variants.size)
    }

    @Test fun rejectsInvalidContracts() {
        fun bad(json: String) = assertThrows(IllegalArgumentException::class.java) { ModelVariants.parse(json) }
        bad("""{"default":"x","variants":[]}""")
        bad(contract.replace("\"default\":\"r544x960\"", "\"default\":\"zzz\""))
        bad(contract.replace("\"id\":\"r544x960\"", "\"id\":\"r384x640\""))
        bad(contract.replace("[544,960]", "[544]"))
        bad(contract.replace("[544,960]", "[0,960]"))
        bad(contract.replace("\"r736x1280\",\"label", "\"../x\",\"label"))
        bad(contract.replace("\"Alta\"", "\" \""))
    }

    @Test fun legacyIsASingleVariantOfTheSpecSize() {
        val v = ModelVariants.legacy(spec(null, 640, 384, 5040))
        assertEquals("legacy", v.default)
        assertEquals(1, v.variants.size)
        assertEquals(384, v.defaultVariant.inputHeight)
        assertEquals(640, v.defaultVariant.inputWidth)
        assertNull(v.saferThan("legacy"))
    }

    @Test fun saferThanPrefersNormalThenSmallest() {
        val v = ModelVariants.parse(contract)
        assertEquals("r384x640", v.saferThan("r736x1280")!!.id)
        assertEquals("r384x640", v.saferThan("r544x960")!!.id)
        // Normal itself failed: offer the smallest of the others.
        assertEquals("r544x960", v.saferThan("r384x640")!!.id)
        val noNormal = ModelVariants("b", listOf(ModelVariant("a", "A", listOf(100, 200)), ModelVariant("b", "B", listOf(50, 80))))
        assertEquals("b", noNormal.saferThan("a")!!.id)
    }

    @Test fun mismatchDetectsSizeAndVariantDisagreement() {
        val v = ModelVariants.parse(contract).byId("r544x960")!!
        assertNull(v.mismatch(spec("r544x960", 960, 544, 10710)))
        assertNull(v.mismatch(spec(null, 960, 544, 10710))) // model.json without a variant field is accepted
        val wrongSize = v.mismatch(spec("r544x960", 640, 384, 5040))
        assertNotNull(wrongSize)
        assertTrue(wrongSize!!, wrongSize.contains("544x960") && wrongSize.contains("384x640"))
        assertTrue(v.mismatch(spec("r384x640", 960, 544, 10710))!!.contains("r384x640"))
    }
}
