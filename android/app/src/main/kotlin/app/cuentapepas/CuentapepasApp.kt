package app.cuentapepas

import android.app.Application
import android.util.Log
import app.cuentapepas.core.store.AtomicFilesDiagnostics
import app.cuentapepas.data.StartupNotices
import app.cuentapepas.core.detect.ModelSpec
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
 * Lazy singletons. [modelSpec] throws `ModelException` (missing/invalid model.json) every time it is read until
 * the problem is fixed; the UI maps the subtype to a Spanish message. [settings] and [exporter] depend on it.
 */
class ServiceLocator(private val app: Application) {
    val store: SessionStore by lazy { SessionStore(app) }

    val startupNotices: StartupNotices by lazy { StartupNotices(app) }

    val modelSpec: ModelSpec by lazy { ModelAssets.loadSpec(app) }

    val settings: SettingsStore by lazy { SettingsStore(app, AppSettings.defaults(modelSpec)) }

    val exporter: Exporter by lazy { Exporter(app, store, modelSpec.classes) }

    /** Creates a detector for the model contract; the caller owns it and must close it. */
    val detectorFactory: (preferGpu: Boolean) -> TfliteDetector =
        { preferGpu -> TfliteDetector(app, modelSpec, preferGpu) }

    val appVersion: String by lazy {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }
}
