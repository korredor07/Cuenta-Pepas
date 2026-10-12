@file:OptIn(ExperimentalLayoutApi::class)

package app.cuentapepas.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SnackbarHostState
import app.cuentapepas.data.PendingNotices
import app.cuentapepas.ui.common.ErrorInfo
import app.cuentapepas.ui.common.describeError
import app.cuentapepas.ui.common.showError
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.cuentapepas.core.calibration.RatioCalibrator
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.loadLotData
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private class LotRow(val lot: Lot, val sessionCount: Int, val calibration: String?, val error: String?)

/** The startup sweep result is recorded into the persisted notices once per process; showing them is separate. */
private var startupRecorded = false

@Composable
fun HomeScreen(
    onOpenLot: (String) -> Unit,
    onBench: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(Unit) {
        val store = services.store
        store.lots().map { lot ->
            try {
                val d = store.loadLotData(lot)
                val set = PassResolver.calibrationSamples(d.sessions, d.plants, d.manualCounts)
                val cal = RatioCalibrator.fit(set.samples, replicates = 50)
                LotRow(lot, d.sessions.count { it.status == SessionStatus.COMPLETED }, Fmt.calibrationStatus(cal.n, cal.usable), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LotRow(lot, 0, null, context.describe(e))
            }
        }
    }

    var showNew by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    var noticeError by remember { mutableStateOf<ErrorInfo?>(null) }
    var pending by remember { mutableStateOf(PendingNotices(emptyList(), emptyList())) }

    // Record what this process's startup sweep found (once per process), then show what is still pending in the
    // preferences: it stays on Home across restarts until the user dismisses it.
    LaunchedEffect(Unit) {
        try {
            if (!startupRecorded) {
                val recovered = services.store.recoveredSessions()
                val issues = services.store.startupIssues()
                services.startupNotices.record(recovered, issues.map { it.path })
                startupRecorded = true
            }
            pending = services.startupNotices.pending()
            noticeError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noticeError = context.describeError(e)
        }
    }

    ScreenScaffold(stringResource(R.string.home_title), onBack = null, snackbar = snack, actions = {
        TextButton(onClick = onBench) { Text(stringResource(R.string.home_bench)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.home_settings)) }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.home_about)) }
    }) {
        Button(onClick = { showNew = true }) { Text(stringResource(R.string.home_new_lot)) }
        noticeError?.let { ErrorText(it.message, it.detail) }
        if (!pending.isEmpty) {
            StartupNoticeCard(pending, onDismiss = {
                scope.launch {
                    try {
                        services.startupNotices.dismissAll()
                        pending = PendingNotices(emptyList(), emptyList())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        snack.showError(context.describe(e), context.getString(R.string.snackbar_close))
                    }
                }
            })
        }
        LoadView(loader) { rows ->
            if (rows.isEmpty()) Text(stringResource(R.string.home_empty))
            for (row in rows) {
                Card(Modifier.fillMaxWidth().clickable { onOpenLot(row.lot.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.lot.name, style = MaterialTheme.typography.titleMedium)
                        if (row.error != null) {
                            ErrorText(stringResource(R.string.home_lot_error, row.error))
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.home_lot_sessions, row.sessionCount))
                                Text(row.calibration.orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        LotEditDialog(initial = null, onDismiss = { showNew = false }, onSave = { form ->
            showNew = false
            scope.launch {
                try {
                    services.store.saveLot(
                        Lot(UUID.randomUUID().toString(), form.name, form.totalPlants, form.cherriesPerKg, System.currentTimeMillis()),
                    )
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

@Composable
private fun StartupNoticeCard(pending: PendingNotices, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_notice_title), style = MaterialTheme.typography.titleMedium)
            if (pending.recovered.isNotEmpty()) {
                Text(stringResource(R.string.home_notice_recovered, pending.recovered.size))
            }
            for (path in pending.corrupt) Text(stringResource(R.string.home_notice_corrupt, path))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_notice_dismiss)) }
        }
    }
}
