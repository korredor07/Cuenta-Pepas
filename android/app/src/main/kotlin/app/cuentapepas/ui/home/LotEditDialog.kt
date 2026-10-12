package app.cuentapepas.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.session.Lot

/** Parsed, validated lot form. */
data class LotForm(val name: String, val totalPlants: Int?, val cherriesPerKg: Double?)

/** Create ([initial] null) or edit a lot. Optional fields stay null when left blank; invalid input shows an error. */
@Composable
fun LotEditDialog(initial: Lot?, onDismiss: () -> Unit, onSave: (LotForm) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var total by remember { mutableStateOf(initial?.totalPlants?.toString().orEmpty()) }
    var perKg by remember { mutableStateOf(initial?.cherriesPerKg?.toString().orEmpty()) }
    var nameError by remember { mutableStateOf(false) }
    var totalError by remember { mutableStateOf(false) }
    var kgError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.lot_dialog_new else R.string.lot_dialog_edit)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameError = false },
                    label = { Text(stringResource(R.string.lot_name)) },
                    isError = nameError,
                    supportingText = { if (nameError) Text(stringResource(R.string.lot_error_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = total, onValueChange = { total = it; totalError = false },
                    label = { Text(stringResource(R.string.lot_total_plants)) },
                    isError = totalError,
                    supportingText = { if (totalError) Text(stringResource(R.string.lot_error_total)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = perKg, onValueChange = { perKg = it; kgError = false },
                    label = { Text(stringResource(R.string.lot_cherries_per_kg)) },
                    isError = kgError,
                    supportingText = {
                        Text(
                            stringResource(if (kgError) R.string.lot_error_kg else R.string.lot_cherries_per_kg_help),
                            color = if (kgError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                val t = total.trim()
                val k = perKg.trim().replace(',', '.')
                val totalVal = if (t.isEmpty()) null else t.toIntOrNull()?.takeIf { it > 0 }
                val kgVal = if (k.isEmpty()) null else k.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                nameError = n.isEmpty()
                totalError = t.isNotEmpty() && totalVal == null
                kgError = k.isNotEmpty() && kgVal == null
                if (!nameError && !totalError && !kgError) onSave(LotForm(n, totalVal, kgVal))
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
