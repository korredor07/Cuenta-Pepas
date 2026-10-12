package app.cuentapepas

import android.app.Application
import android.util.Log
import app.cuentapepas.core.store.AtomicFilesDiagnostics
import app.cuentapepas.data.StartupNotices
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.detect.ModelVariants
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.data.Exporter
import app.cuentapepas.data.SessionStore
import app.cuentapepas.data.SettingsStore
import app.cuentapepas.inference.ModelAssets
import app.cuentapepas.inference.TfliteDetector

/** Application class; owns the [ServiceLocator]. */
class CuentapepasApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        AtomicFilesDiagnostics.listener = { file, cause ->
            Log.w("Cuentapepas", "ATOMIC_MOVE no disponible para $file; se usó un reemplazo no atómico", cause)
        }
        services = ServiceLocator(this)
        app.cuentapepas.data.Diagnostics.installCrashHandler(
            this,
            appVersion = { services.appVersion },
            modelId = { services.modelSpec.modelId },
        )
    }
}

/**
 * Lazy singletons. [variants] and [modelSpec] throw `ModelException` (missing/invalid files) every time they are read
 * until the problem is fixed; the UI maps the subtype to a Spanish message. [settings] and [exporter] depend on them.
 */
class ServiceLocator(private val app: Application) {
    val store: SessionStore by lazy { SessionStore(app) }

    val startupNotices: StartupNotices by lazy { StartupNotices(app) }

    /** The detector resolutions shipped in the app (legacy flat layout = one variant). */
    val variants: ModelVariants by lazy { ModelAssets.loadVariants(app) }

    private val specs = java.util.concurrent.ConcurrentHashMap<String, ModelSpec>()

    /** Contract of one resolution variant; verified against `variants.json`. */
    fun modelSpec(variantId: String): ModelSpec =
        specs[variantId] ?: ModelAssets.loadSpec(app, variants, variantId).also { specs[variantId] = it }

    /** Contract of the default variant: classes and thresholds are shared by every variant. */
    val modelSpec: ModelSpec get() = modelSpec(variants.default)

    val settings: SettingsStore by lazy { SettingsStore(app, AppSettings.defaults(modelSpec, variants)) }

    val exporter: Exporter by lazy { Exporter(app, store, modelSpec.classes) }

    /** Creates a detector for [variantId]; the caller owns it and must close it. */
    val detectorFactory: (variantId: String, preferGpu: Boolean) -> TfliteDetector =
        { variantId, preferGpu -> TfliteDetector(app, modelSpec(variantId), variantId, preferGpu) }

    val appVersion: String by lazy {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }
}
