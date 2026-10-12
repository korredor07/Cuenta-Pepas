package app.cuentapepas.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.cuentapepas.R

/** Small, unobtrusive hint of the working distance, shown under the status bar (never over the counts panel). */
@Composable
internal fun DistanceHint(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.live_distance_hint),
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
