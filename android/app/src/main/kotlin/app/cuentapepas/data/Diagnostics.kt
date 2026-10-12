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

    /** Installs the uncaught-exception handler; the previous handler still runs afterwards. */
    fun installCrashHandler(context: Context, appVersion: () -> String?, modelId: () -> String?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = File(context.filesDir, "logs").apply { mkdirs() }
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(dir, "crash-${stamp()}.txt").writeText(
                    deviceInfo(context, runCatching(appVersion).getOrNull(), runCatching(modelId).getOrNull()) +
                        "Hilo: ${thread.name}\n\n$sw",
                )
                dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }
                    ?.dropLast(MAX_CRASH_FILES)?.forEach { it.delete() }
            } catch (e: Exception) {
                android.util.Log.e("Cuentapepas", "No se pudo guardar el informe de fallo", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Builds `filesDir/exports/diagnostico-<ts>.zip`. Failures propagate (IOException ...). */
    suspend fun build(context: Context, services: ServiceLocator): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("diagnostico-") }?.forEach { it.delete() }
        val out = File(dir, "diagnostico-${stamp()}.zip")
        val problems = ArrayList<String>()
        val modelId = try { services.modelSpec.modelId } catch (e: Exception) { problems += "modelo: $e"; null }
        val version = try { services.appVersion } catch (e: Exception) { problems += "versión: $e"; null }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("dispositivo.txt", deviceInfo(context, version, modelId))

            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}"),
                )
                val log = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                put("logcat.txt", log + if (err.isNotBlank()) "\n--- stderr ---\n$err" else "")
            } catch (e: IOException) {
                problems += "logcat: $e"
            }

            File(context.filesDir, "logs").listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }
                ?.forEach { put("fallos/${it.name}", it.readText()) }

            try {
                val s = services.settings.snapshot()
                put("ajustes.txt", s.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} = ${it.value}" })
            } catch (e: Exception) {
                problems += "ajustes: $e"
            }

            try {
                val lines = services.store.sessions(null).sortedBy { it.seq }.map {
                    "${it.id} lote=${it.lotId} fila=${it.row}${it.side} estado=${it.status} " +
                        "inicio=${it.startedAtMs} fin=${it.endedAtMs ?: "-"} modelo=${it.modelId} app=${it.appVersion} " +
                        "evaluacion=${it.evaluationMode} calibracion=${it.isCalibrationSample}"
                }
                put("sesiones.txt", lines.joinToString("\n"))
            } catch (e: Exception) {
                problems += "sesiones: $e"
            }

            val latest = File(context.filesDir, "bench").listFiles { f -> f.name.endsWith(".json") }?.maxByOrNull { it.name }
            if (latest != null) put("benchmark/${latest.name}", latest.readText())

            if (problems.isNotEmpty()) put("problemas.txt", problems.joinToString("\n"))
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
