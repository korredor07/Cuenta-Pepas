package app.cuentapepas

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import app.cuentapepas.ui.theme.Brand
import app.cuentapepas.ui.live.HardwareKeys
import app.cuentapepas.ui.nav.AppNav
import app.cuentapepas.ui.theme.CuentapepasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Brand.Ink.toArgb()))
        setContent {
            CuentapepasTheme {
                AppNav()
            }
        }
    }

    /** Volume keys advance to the next plant while the live screen is active; otherwise the system handles them. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Key repeats (button held) must not skip several plants.
            val consumed = if (event.repeatCount == 0) HardwareKeys.onVolumeKey() else HardwareKeys.liveScreenActive
            if (consumed) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
