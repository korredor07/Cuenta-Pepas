package app.cuentapepas.ui.common

import app.cuentapepas.ui.theme.brandCardElevation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.cuentapepas.core.store.CorruptSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Persistent red banner "N recorridos ilegibles no se incluyeron" with a "Ver / descartar" action. It is shown while
 * any session on disk cannot be read (those sessions are left out of every listing, so the lot's numbers do not
 * include them). Discarding needs a confirmation. [onChanged] runs after a session was discarded.
 */
@Composable
fun CorruptSessionsBanner(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val store = context.services.store
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(Unit) { store.corruptSessions() }
    var listOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<CorruptSession?>(null) }
    var failure by remember { mutableStateOf<ErrorInfo?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    when (val s = loader.state.value) {
        Load.Loading -> {}
        is Load.Failed -> ErrorText(s.message, s.detail)
        is Load.Ok -> {
            val items = s.value
            if (items.isEmpty()) {
                listOpen = false
                return
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = brandCardElevation(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        context.resources.getQuantityString(R.plurals.corrupt_banner, items.size, items.size),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { listOpen = true }) { Text(stringResource(R.string.corrupt_view)) }
                }
            }
            if (listOpen) {
                AlertDialog(
                    onDismissRequest = { listOpen = false },
                    title = { Text(stringResource(R.string.corrupt_list_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.corrupt_list_help), style = MaterialTheme.typography.bodySmall)
                            failure?.let { ErrorText(it.message, it.detail) }
                            refused?.let { ErrorText(it) }
                            for (c in items) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.id, style = MaterialTheme.typography.titleSmall)
                                        Text(c.error.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { confirming = c }) { Text(stringResource(R.string.corrupt_discard)) }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { listOpen = false }) { Text(stringResource(R.string.corrupt_close)) } },
                )
            }
        }
    }

    confirming?.let { c ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.corrupt_discard_title)) },
            text = { Text(stringResource(R.string.corrupt_discard_text, c.id)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch {
                        try {
                            val deleted = store.discardCorruptSession(c.id)
                            failure = null
                            refused = if (deleted) null else context.getString(R.string.corrupt_discard_refused, c.id)
                            loader.reload()
                            onChanged()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = context.describeError(e)
                        }
                    }
                }) { Text(stringResource(R.string.corrupt_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
