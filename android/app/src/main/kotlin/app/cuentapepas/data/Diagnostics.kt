package app.cuentapepas.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.cuentapepas.R
import app.cuentapepas.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Crash files and the "Compartir diagnóstico" zip. Contains no GPS coordinates. */
object Diagnostics {
    private const val AUTHORITY = "app.cuentapepas.fileprovider"
    private const val MAX_CRASH_FILES = 10

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    fun deviceInfo(context: Context, appVersion: String?, modelId: String?): String = buildString {
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("SOC: ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Aplicación: ${context.packageName} ${appVersion ?: "desconocida"}")
        appendLine("Modelo: ${modelId ?: "no disponible"}")
    }

    private fun valueOrError(f: () -> String?): String? =
        try { f() } catch (e: Throwable) { e.toString() }

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    /** Installs the uncaught-exception handler; the previous handler always runs afterwards. */
    fun installCrashHandler(context: Context, appVersion: () -> String?, modelId: () -> String?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = File(context.filesDir, "logs").apply { mkdirs() }
                val stampMs = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
                File(dir, "crash-$stampMs.txt").writeText(
                    deviceInfo(context, valueOrError(appVersion), valueOrError(modelId)) +
                        "Hilo: ${thread.name}\n\n${stackTrace(error)}",
                )
                dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }
                    ?.dropLast(MAX_CRASH_FILES)?.forEach { it.delete() }
            } catch (t: Throwable) {
                android.util.Log.e("Cuentapepas", "No se pudo guardar el informe de fallo", t)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Builds `filesDir/exports/diagnostico-<ts>.zip`. Failures propagate (IOException ...). */
    suspend fun build(context: Context, services: ServiceLocator): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val out = File(dir, "diagnostico-${stamp()}.zip")
        val problems = ArrayList<String>()
        fun problem(section: String, e: Throwable) {
            android.util.Log.e("Cuentapepas", "Diagnóstico: falló la sección $section", e)
            problems += "$section: $e\n${stackTrace(e)}"
        }
        fun problemText(section: String, msg: String) {
            android.util.Log.e("Cuentapepas", "Diagnóstico: $section: $msg")
            problems += "$section: $msg"
        }
        val modelId = try { services.modelSpec.modelId } catch (e: Exception) { problem("modelo", e); null }
        val version = try { services.appVersion } catch (e: Exception) { problem("versión", e); null }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("dispositivo.txt", deviceInfo(context, version, modelId))

            try {
                val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true).start()
                try {
                    val log = proc.inputStream.bufferedReader().use { it.readText() }
                    val code = proc.waitFor()
                    put("logcat.txt", log)
                    if (code != 0) problemText("logcat", "terminó con código $code")
                } finally {
                    proc.destroy()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                problem("logcat (interrumpido)", e)
            } catch (e: IOException) {
                problem("logcat", e)
            }

            File(context.filesDir, "logs").listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }
                ?.forEach {
                    try {
                        put("fallos/${it.name}", it.readText())
                    } catch (e: IOException) {
                        problem("fallo ${it.name}", e)
                    }
                }

            try {
                val s = services.settings.snapshot()
                put("ajustes.txt", s.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} = ${it.value}" })
            } catch (e: Exception) {
                problem("ajustes", e)
            }

            try {
                val lines = services.store.sessions(null).sortedBy { it.seq }.map {
                    "${it.id} lote=${it.lotId} fila=${it.row}${it.side} estado=${it.status} " +
                        "inicio=${it.startedAtMs} fin=${it.endedAtMs ?: "-"} modelo=${it.modelId} app=${it.appVersion} " +
                        "variante=${it.settings["detection.variant"] ?: "sin dato"} " +
                        "evaluacion=${it.evaluationMode} calibracion=${it.isCalibrationSample}"
                }
                put("sesiones.txt", lines.joinToString("\n"))
            } catch (e: Exception) {
                problem("sesiones", e)
            }

            // Latest benchmark JSON per variant (file names are timestamps, so the max name is the newest).
            val latestPerVariant = LinkedHashMap<String, Pair<File, String>>()
            File(context.filesDir, "bench").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                try {
                    val text = f.readText()
                    val variant = try {
                        Json.parseToJsonElement(text).jsonObject["summary"]?.jsonObject?.get("variant")?.jsonPrimitive?.content
                    } catch (e: Exception) {
                        problem("benchmark ${f.name} (variante ilegible)", e)
                        null
                    } ?: "sin-variante"
                    latestPerVariant[variant] = f to text
                } catch (e: IOException) {
                    problem("benchmark ${f.name}", e)
                }
            }
            latestPerVariant.values.forEach { (f, text) -> put("benchmark/${f.name}", text) }

            if (problems.isNotEmpty()) put("problemas.txt", problems.joinToString("\n\n"))
        }
        // Old zips are removed only once the new one exists in full.
        dir.listFiles { f -> f.name.startsWith("diagnostico-") && f != out }?.forEach {
            if (!it.delete()) android.util.Log.w("Cuentapepas", "No se pudo borrar el diagnóstico anterior ${it.name}")
        }
        out
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.diag_share_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
