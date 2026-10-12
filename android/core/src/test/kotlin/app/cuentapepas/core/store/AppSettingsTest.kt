package app.cuentapepas.core.store

import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.ModelVariants
import app.cuentapepas.core.quality.QualityThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsTest {
    private class MemKv : KeyValueStore {
        val m = HashMap<String, String>()
        override fun all(): Map<String, String> = HashMap(m)
        override fun putAll(entries: Map<String, String>) {
            m.putAll(entries)
        }
        override fun clear() = m.clear()
    }

    private val spec = ModelSpec.parse(javaClass.getResource("/model_example.json")!!.readText())

    @Test fun defaultsComeFromModelContract() {
        val d = AppSettings.defaults(spec)
        assertEquals(0.30f, d.scoreThreshold, 0f)
        assertEquals(0.45f, d.nmsIou, 0f)
    }

    @Test fun saveLoadRoundTripAndSnapshot() {
        val repo = SettingsRepository(MemKv(), AppSettings.defaults(spec))
        assertEquals(AppSettings.defaults(spec), repo.load())
        val s = AppSettings(
            quality = QualityThresholds(minSharpness = 33.5, onFrames = 7),
            minHits = 5,
            scoreThreshold = 0.25f,
            gpsMaxAccuracyM = 6f,
        )
        repo.save(s)
        assertEquals(s, repo.load())
        assertEquals("33.5", s.snapshot()["quality.minSharpness"])
        assertEquals("5", s.snapshot()["counter.minHits"])
        repo.reset()
        assertEquals(AppSettings.defaults(spec), repo.load())
    }

    @Test fun unreadableValueIsCorruptNotDefaulted() {
        val kv = MemKv().apply { m["counter.minHits"] = "abc" }
        val e = assertThrows(CorruptDataException::class.java) { SettingsRepository(kv, AppSettings()).load() }
        assertEquals("settings:counter.minHits", e.path)
        kv.m["counter.minHits"] = "0"
        assertThrows(CorruptDataException::class.java) { SettingsRepository(kv, AppSettings()).load() }
    }

    @Test fun detectionVariantDefaultsFromVariantsRoundTripsAndIsInTheSnapshot() {
        val variants = ModelVariants.parse(
            """{"default":"r544x960","variants":[{"id":"r384x640","label_es":"Normal","input":[384,640]},
               {"id":"r544x960","label_es":"Alta","input":[544,960]}]}""",
        )
        val d = AppSettings.defaults(spec, variants)
        assertEquals("r544x960", d.detectionVariant)
        assertEquals("r544x960", d.snapshot()["detection.variant"])
        assertEquals("legacy", AppSettings.defaults(spec).detectionVariant)
        val repo = SettingsRepository(MemKv(), d)
        repo.save(d.copy(detectionVariant = "r384x640"))
        assertEquals("r384x640", repo.load().detectionVariant)
        // A session snapshot without the key (older app) keeps the base value.
        val old = d.snapshot() - "detection.variant"
        assertEquals("r544x960", AppSettings.fromMap(old, d).detectionVariant)
        assertEquals("r384x640", AppSettings.fromMap(d.snapshot() + ("detection.variant" to "r384x640"), d).detectionVariant)
        assertThrows(CorruptDataException::class.java) { AppSettings.fromMap(mapOf("detection.variant" to "../x"), d) }
        assertThrows(CorruptDataException::class.java) { AppSettings.fromMap(mapOf("detection.variant" to ""), d) }
    }
}
