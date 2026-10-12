package app.cuentapepas.ui.live

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cuentapepas.R

/**
 * Live camera counting screen for an ACTIVE session created by the new-session screen.
 * Contract (stable for navigation): [sessionId] is an existing ACTIVE session; [onFinished] is called with the same
 * id after the session was completed (or abandoned) and saved, so navigation can open the summary.
 *
 * Landscape, screen kept on. Volume keys advance to the next plant through [HardwareKeys] (MainActivity forwards
 * them). Camera permission is required; location is optional (without it the status bar says "GPS: sin permiso").
 */
@Composable
fun LiveScreen(sessionId: String, onFinished: (sessionId: String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: LiveViewModel = viewModel(key = "live-$sessionId", factory = LiveViewModel.Factory(app, sessionId))
    val phase by vm.phase.collectAsState()
    val finished by vm.finished.collectAsState()

    LaunchedEffect(finished) { finished?.let(onFinished) }

    // Keep the screen on while this screen is shown.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Volume keys -> next plant (MainActivity forwards them while liveScreenActive).
    DisposableEffect(Unit) {
        HardwareKeys.liveScreenActive = true
        onDispose { HardwareKeys.liveScreenActive = false }
    }
    LaunchedEffect(vm) { HardwareKeys.nextPlant.collect { vm.nextPlant() } }

    // Permissions.
    var cameraGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        locationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun requestMissing() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            .filterNot { context.isGranted(it) }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    LaunchedEffect(Unit) { requestMissing() }

    // Lifecycle: leaving the app pauses and persists; coming back may restart the evaluation video; a visit to the
    // system settings may have granted permissions.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        locationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }

    val isReady = phase is LivePhase.Ready
    LaunchedEffect(isReady, locationGranted) {
        if (isReady) vm.startLocation() // reports "sin permiso" itself when the permission is missing
    }

    val onBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val hud by vm.hud.collectAsState()
    val leave by vm.leave.collectAsState()
    var confirmFinish by remember { mutableStateOf(false) }
    var allowLeave by remember { mutableStateOf(false) }

    // Leaving the screen (session discarded, or left from a failure panel): disable our back handler, then go back.
    LaunchedEffect(leave) { if (leave) allowLeave = true }
    LaunchedEffect(allowLeave) { if (allowLeave) onBackDispatcher?.onBackPressed() }

    // Back never silently drops an ACTIVE session: while counting it asks first, while saving it is ignored, from a
    // failure panel it saves what exists before leaving, and while the save failed the buttons decide.
    BackHandler(enabled = !allowLeave && phase !is LivePhase.Loading) {
        when (phase) {
            is LivePhase.Ready -> confirmFinish = true
            is LivePhase.Failed -> vm.leaveSaving()
            is LivePhase.Finishing, is LivePhase.SaveFailed, LivePhase.Loading -> Unit
        }
    }

    // The camera view stays composed (and bound) from Ready through Finishing, so the recording can be stopped
    // before the camera is unbound.
    val lastReady = remember { arrayOfNulls<LivePhase.Ready>(1) }
    (phase as? LivePhase.Ready)?.let { lastReady[0] = it }
    val cameraReady = (phase as? LivePhase.Ready) ?: lastReady[0]?.takeIf { phase is LivePhase.Finishing }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraReady != null && cameraGranted) {
            LiveContent(vm, cameraReady, onRequestFinish = { confirmFinish = true })
        }
        when (val p = phase) {
            LivePhase.Loading -> Message(stringResource(R.string.live_loading), showProgress = true)
            LivePhase.Finishing -> Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
                Message(stringResource(R.string.live_saving), showProgress = true)
            }
            is LivePhase.Failed -> ErrorPanel(
                message = p.message,
                actions = buildList {
                    p.variantOffer?.let { o -> add(stringResource(R.string.variant_use, o.label) to { vm.useVariant(o.variantId) }) }
                    if (p.canAbandon) add(stringResource(R.string.live_close_session) to { vm.finish() })
                    add(stringResource(R.string.live_back) to { vm.leaveSaving() })
                },
            )
            is LivePhase.SaveFailed -> ErrorPanel(
                message = p.message,
                actions = listOf(
                    stringResource(R.string.live_retry) to { vm.finish() },
                    stringResource(R.string.live_discard_exit) to { vm.discardAndExit() },
                ),
            )
            is LivePhase.Ready ->
                if (!cameraGranted) {
                    CameraPermissionPanel(
                        onGrant = { requestMissing() },
                        onSettings = { context.openAppSettings() },
                        onClose = { confirmFinish = true },
                    )
                }
        }
    }

    if (confirmFinish && phase is LivePhase.Ready) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(stringResource(R.string.live_finish_title)) },
            text = {
                val total = hud.sessionCounts?.total
                Text(
                    if (hud.hasProcessedFrames && total != null) {
                        stringResource(R.string.live_finish_message, total)
                    } else {
                        stringResource(R.string.live_finish_no_frames)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finish() }) {
                    Text(stringResource(R.string.live_finish_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.live_cancel)) }
            },
        )
    }
}

@Composable
private fun LiveContent(vm: LiveViewModel, ready: LivePhase.Ready, onRequestFinish: () -> Unit) {
    val hud by vm.hud.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val pv = previewView
    LaunchedEffect(pv, lifecycleOwner) {
        if (pv != null) vm.bindCamera(lifecycleOwner, pv, displayRotation(context, pv))
    }
    // While the session is being finished the view model keeps the camera bound until the recording is stopped.
    DisposableEffect(Unit) { onDispose { vm.unbindCamera() } }

    // The phone may be turned 180 degrees (sensor landscape): keep the use cases' rotation in step with the display.
    DisposableEffect(pv) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = pv?.display ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                if (display.displayId == displayId) vm.camera.setRotation(display.rotation)
            }
        }
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView-backed: composes reliably under the overlay and transforms exactly like FILL_CENTER.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { previewView = it },
        )
        DetectionOverlay(
            frame = vm.frame,
            countedIds = vm.countedIds,
            minHits = ready.minHits,
            minMeanScore = ready.minMeanScore,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            StatusBar(hud, ready.evaluationMode, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DistanceHint()
                    WarningsBanner(hud.warnings)
                    if (hud.paused) PausedBanner()
                    NoticesList(hud.notices, Modifier.widthIn(max = 460.dp))
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { vm.setPaused(!hud.paused) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text(
                                if (hud.paused) stringResource(R.string.live_resume) else stringResource(R.string.live_pause),
                                fontSize = 16.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = onRequestFinish,
                            modifier = Modifier.heightIn(min = 56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
                        ) { Text(stringResource(R.string.live_finish), fontSize = 16.sp) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(240.dp).fillMaxHeight()) {
                    CountsPanel(
                        classes = ready.classes,
                        plantNumber = hud.plantNumber,
                        plantCounts = hud.plantCounts,
                        routeCounts = hud.sessionCounts,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { vm.nextPlant() },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    ) {
                        Text(
                            stringResource(R.string.live_next_plant),
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Current display rotation for the camera use cases. The preview's own display can be null before it is attached;
 * then the default display from the DisplayManager is used, and only if that is missing too, landscape.
 */
private fun displayRotation(context: Context, previewView: PreviewView): Int {
    previewView.display?.let { return it.rotation }
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90
}

@Composable
private fun Message(text: String, showProgress: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
        }
        Text(text, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Full-screen explanation of a failure that makes counting impossible; never a black screen, never fake data.
 * Each action is a (label, handler) pair shown as a button.
 */
@Composable
private fun ErrorPanel(message: String, actions: List<Pair<String, () -> Unit>>) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            actions.forEachIndexed { i, (label, handler) ->
                if (i == 0) {
                    Button(onClick = handler, modifier = Modifier.heightIn(min = 56.dp)) { Text(label, fontSize = 16.sp) }
                } else {
                    OutlinedButton(
                        onClick = handler,
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text(label, fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionPanel(onGrant: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.live_camera_permission_title),
            color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.live_camera_permission_message),
            color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGrant, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.live_camera_permission_grant))
            }
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text(stringResource(R.string.live_open_settings)) }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.live_close_session), color = Color.White)
            }
        }
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
