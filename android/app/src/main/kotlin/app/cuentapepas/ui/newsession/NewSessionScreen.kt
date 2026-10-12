package app.cuentapepas.ui.newsession

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.session.Side
import app.cuentapepas.inference.ModelException
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Form for a new pass. Creates the session ACTIVE through the store (which assigns `seq`) and calls [onStarted] with
 * its id. An existing COMPLETED pass of the same row and side is only replaced when the new session completes
 * (SessionStore.completeSession), so here the user just confirms.
 */
@Composable
fun NewSessionScreen(lotId: String, onBack: () -> Unit, onStarted: (sessionId: String) -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()

    var row by remember { mutableStateOf("1") }
    var side by remember { mutableStateOf(Side.A) }
    var firstPlant by remember { mutableStateOf("1") }
    var step by remember { mutableStateOf(1) }
    var calibration by remember { mutableStateOf(false) }
    var evaluation by remember { mutableStateOf(false) }
    var rowError by remember { mutableStateOf(false) }
    var plantError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<SessionMeta?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The model is only needed to record; lots stay usable without it. Read it off the main thread.
    LaunchedEffect(Unit) {
        modelError = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { services.modelSpec }
            null
        } catch (e: ModelException) {
            Log.e("Cuentapepas", "Model unavailable for a new session", e)
            context.describe(e)
        }
    }

    fun create() {
        busy = true
        scope.launch {
            try {
                val rowNo = row.trim().toInt()
                val plantNo = firstPlant.trim().toInt()
                val settings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { services.settings.snapshot() }
                val spec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    services.modelSpec(settings.getValue("detection.variant"))
                }
                val meta = SessionMeta(
                    id = UUID.randomUUID().toString(),
                    lotId = lotId,
                    row = rowNo,
                    side = side,
                    firstPlant = plantNo,
                    plantStep = step,
                    isCalibrationSample = calibration,
                    evaluationMode = evaluation,
                    startedAtMs = System.currentTimeMillis(),
                    endedAtMs = null,
                    status = SessionStatus.ACTIVE,
                    appVersion = services.appVersion,
                    modelId = spec.modelId,
                    settings = settings,
                    seq = 0L, // assigned by the store
                )
                val created = services.store.createSession(meta)
                error = null
                onStarted(created.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Cuentapepas", "Cannot create the session", e)
                error = context.describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun start() {
        val rowNo = row.trim().toIntOrNull()?.takeIf { it > 0 }
        val plantNo = firstPlant.trim().toIntOrNull()?.takeIf { it > 0 }
        rowError = rowNo == null
        plantError = plantNo == null
        if (rowNo == null || plantNo == null) return
        busy = true
        scope.launch {
            try {
                val sessions = services.store.sessions(lotId)
                val existing = PassResolver.conflictingSession(sessions, lotId, rowNo, side)
                busy = false
                if (existing != null) conflict = existing else create()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Cuentapepas", "Cannot check for a repeated pass", e)
                busy = false
                error = context.describe(e)
            }
        }
    }

    ScreenScaffold(stringResource(R.string.new_title), onBack) {
        modelError?.let { ErrorText(it) }
        error?.let { ErrorText(it) }
        OutlinedTextField(
            value = row, onValueChange = { row = it; rowError = false },
            label = { Text(stringResource(R.string.new_row)) },
            isError = rowError,
            supportingText = { if (rowError) Text(stringResource(R.string.new_error_row)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Text(stringResource(R.string.new_side), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Side.entries) {
                FilterChip(selected = side == s, onClick = { side = s }, label = { Text(s.name) })
            }
        }
        OutlinedTextField(
            value = firstPlant, onValueChange = { firstPlant = it; plantError = false },
            label = { Text(stringResource(R.string.new_first_plant)) },
            isError = plantError,
            supportingText = { if (plantError) Text(stringResource(R.string.new_error_plant)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Text(stringResource(R.string.new_direction), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = step == 1, onClick = { step = 1 }, label = { Text(stringResource(R.string.new_direction_up)) })
            FilterChip(selected = step == -1, onClick = { step = -1 }, label = { Text(stringResource(R.string.new_direction_down)) })
        }
        // A descending pass cannot go below plant 1: say so before it starts, not at the end of the row.
        if (step == -1) Text(stringResource(R.string.new_descending_note), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = calibration, onCheckedChange = { calibration = it })
            Text(stringResource(R.string.new_calibration))
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = evaluation, onCheckedChange = { evaluation = it })
            Text(stringResource(R.string.new_evaluation))
        }
        Button(onClick = ::start, enabled = !busy && modelError == null) { Text(stringResource(R.string.new_start)) }
    }

    conflict?.let { existing ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text(stringResource(R.string.new_conflict_title)) },
            text = { Text(stringResource(R.string.new_conflict_message, Fmt.dateTime(existing.endedAtMs ?: existing.startedAtMs))) },
            confirmButton = {
                TextButton(onClick = { conflict = null; create() }) { Text(stringResource(R.string.new_conflict_replace)) }
            },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
