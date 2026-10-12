package app.cuentapepas.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelVariants

/** Display text of a variant: its Spanish label and the model input size. */
@Composable
fun variantText(v: app.cuentapepas.core.detect.ModelVariant): String =
    stringResource(R.string.variant_option, v.labelEs, v.inputWidth, v.inputHeight)

/**
 * Selector of the detector input resolution (one radio row per variant) with the battery/FPS note.
 * [selected] is the id of the chosen variant; [enabled] false freezes it (e.g. while a benchmark runs).
 */
@Composable
fun VariantSelector(
    variants: ModelVariants,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    showNote: Boolean = true,
) {
    Column(Modifier.selectableGroup()) {
        for (v in variants.variants) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = v.id == selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(v.id) },
                ).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = v.id == selected, onClick = null, enabled = enabled)
                Text(variantText(v), Modifier.padding(start = 12.dp))
            }
        }
        if (showNote) {
            Text(stringResource(R.string.settings_variant_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
