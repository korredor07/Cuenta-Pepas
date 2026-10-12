@file:OptIn(ExperimentalLayoutApi::class)

package app.cuentapepas.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.Lot
import androidx.compose.material3.SnackbarHostState
import app.cuentapepas.ui.common.CorruptSessionsBanner
import app.cuentapepas.ui.common.showError
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.loadLotData
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A lot with its sessions; entry point to a new pass, the calibration and the estimate. */
@Composable
fun LotScreen(
    lotId: String,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCalibration: () -> Unit,
    onEstimate: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(lotId) { services.store.loadLotData(lotId) }
    var editing by remember { mutableStateOf<Lot?>(null) }
    val snack = remember { SnackbarHostState() }

    ScreenScaffold("", onBack, snackbar = snack, actions = {
        TextButton(onClick = { (loader.state.value as? app.cuentapepas.ui.common.Load.Ok)?.let { editing = it.value.lot } }) {
            Text(stringResource(R.string.lot_screen_edit))
        }
    }) {
        CorruptSessionsBanner { loader.reload() }
        LoadView(loader) { d ->
            Text(d.lot.name, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.lot_total_plants_line, d.lot.totalPlants?.toString() ?: Fmt.NO_DATA))
            Text(stringResource(R.string.lot_cherries_per_kg_line, Fmt.orNoData(d.lot.cherriesPerKg, 0)))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onNewSession) { Text(stringResource(R.string.lot_new_session)) }
                OutlinedButton(onClick = onCalibration) { Text(stringResource(R.string.lot_calibration)) }
                OutlinedButton(onClick = onEstimate) { Text(stringResource(R.string.lot_estimate)) }
            }
            Text(stringResource(R.string.lot_sessions_title), style = MaterialTheme.typography.titleMedium)
            if (d.sessions.isEmpty()) Text(stringResource(R.string.lot_no_sessions))
            for (s in d.sessions.sortedByDescending { it.seq }) {
                val status = stringResource(
                    when (s.status) {
                        app.cuentapepas.core.session.SessionStatus.ACTIVE -> R.string.session_status_ACTIVE
                        app.cuentapepas.core.session.SessionStatus.COMPLETED -> R.string.session_status_COMPLETED
                        app.cuentapepas.core.session.SessionStatus.REPLACED -> R.string.session_status_REPLACED
                        app.cuentapepas.core.session.SessionStatus.ABANDONED -> R.string.session_status_ABANDONED
                    },
                )
                Text(
                    stringResource(R.string.lot_session_line, s.row, s.side.name, Fmt.dateTime(s.startedAtMs), status),
                    Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }.padding(vertical = 8.dp),
                )
            }
        }
    }

    editing?.let { lot ->
        LotEditDialog(initial = lot, onDismiss = { editing = null }, onSave = { form ->
            editing = null
            scope.launch {
                try {
                    services.store.saveLot(lot.copy(name = form.name, totalPlants = form.totalPlants, cherriesPerKg = form.cherriesPerKg))
                    loader.reload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snack.showError(context.describe(e), context.getString(R.string.snackbar_close))
                }
            }
        })
    }
}
