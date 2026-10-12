package app.cuentapepas.ui.common

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import app.cuentapepas.core.store.LotHasSessionsException
import app.cuentapepas.core.store.SessionNotFoundException
import app.cuentapepas.inference.TfliteDetector
import java.io.IOException
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cuentapepas.CuentapepasApp
import app.cuentapepas.R
import app.cuentapepas.ServiceLocator
import app.cuentapepas.core.session.DataIntegrityException
import app.cuentapepas.core.store.CorruptDataException
import app.cuentapepas.core.store.LotNotFoundException
import app.cuentapepas.inference.ModelException
import app.cuentapepas.inference.ModelInvalidException
import app.cuentapepas.inference.ModelLoadException
import app.cuentapepas.inference.ModelMissingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Context.services: ServiceLocator get() = (applicationContext as CuentapepasApp).services

/** Thrown by screens when they already know the exact Spanish message to show (no technical detail). */
class UserMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A Spanish [message] for the user plus the optional technical [detail] (shown only in a collapsible line). */
data class ErrorInfo(val message: String, val detail: String?)

private const val LOG_TAG = "Cuentapepas"

private fun technical(e: Throwable): String {
    val parts = ArrayList<String>()
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 4) {
        parts += "${t::class.java.simpleName}: ${t.message ?: "-"}"
        t = t.cause
        depth++
    }
    return parts.joinToString(" <- ")
}

/**
 * Spanish, user-facing description of a failure plus its technical detail. Every call logs the error with its stack
 * trace (tag "Cuentapepas"). Typed exceptions get specific messages; anything else gets a generic Spanish message
 * and the original text goes to [ErrorInfo.detail], never to the main message.
 */
fun Context.describeError(e: Throwable): ErrorInfo {
    Log.e(LOG_TAG, "Error mostrado al usuario: ${e.message}", e)
    val detail = technical(e)
    return when (e) {
        is UserMessageException -> ErrorInfo(e.message.orEmpty(), e.cause?.let(::technical))
        is ModelMissingException -> ErrorInfo(getString(R.string.model_missing, e.assetPath), detail)
        is ModelInvalidException -> ErrorInfo(getString(R.string.model_invalid, e.message.orEmpty()), e.cause?.let(::technical))
        is ModelLoadException -> ErrorInfo(getString(R.string.model_load_failed, e.message.orEmpty()), e.cause?.let(::technical))
        is TfliteDetector.ContractViolation -> ErrorInfo(getString(R.string.error_contract), detail)
        is CorruptDataException -> ErrorInfo(getString(R.string.data_corrupt, e.path), detail)
        is DataIntegrityException -> ErrorInfo(getString(R.string.error_integrity, e.message.orEmpty()), detail)
        is LotNotFoundException -> ErrorInfo(getString(R.string.error_lot_missing), detail)
        is SessionNotFoundException -> ErrorInfo(getString(R.string.error_session_missing), detail)
        is LotHasSessionsException -> ErrorInfo(e.message.orEmpty(), null) // already Spanish
        is IllegalArgumentException -> ErrorInfo(getString(R.string.error_invalid_argument), detail)
        is IllegalStateException -> ErrorInfo(getString(R.string.error_invalid_state), detail)
        is IOException -> ErrorInfo(getString(R.string.error_io), detail)
        else -> ErrorInfo(getString(R.string.error_unexpected), detail)
    }
}

/** Main Spanish message of [describeError]. */
fun Context.describe(e: Throwable): String = describeError(e).message

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String, val detail: String? = null) : Load<Nothing>
}

class Loader<T>(val state: State<Load<T>>, val reload: () -> Unit)

/** Runs [block] off the main thread whenever [key] changes or [Loader.reload] is called; failures become [Load.Failed]. */
@Composable
fun <T> rememberLoader(key: Any?, block: suspend () -> T): Loader<T> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state = remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, tick) {
        state.value = Load.Loading // a reload shows progress instead of the previous result
        try {
            val result = withContext(Dispatchers.Default) { block() }
            state.value = Load.Ok(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val info = context.describeError(e)
            state.value = Load.Failed(info.message, info.detail)
        }
    }
    return remember(state) { Loader(state) { tick++ } }
}

@Composable
fun <T> ColumnScope.LoadView(loader: Loader<T>, content: @Composable ColumnScope.(T) -> Unit) {
    when (val s = loader.state.value) {
        Load.Loading -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.common_loading))
        }
        is Load.Failed -> {
            ErrorText(s.message, s.detail)
            Button(onClick = loader.reload) { Text(stringResource(R.string.common_retry)) }
        }
        is Load.Ok -> content(s.value)
    }
}

/** Red message; when [detail] is given it sits behind a collapsible "Detalle técnico" toggle. */
@Composable
fun ErrorText(message: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (detail != null) {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) {
                Text(stringResource(R.string.error_technical_detail) + if (open) " ▲" else " ▼", style = MaterialTheme.typography.labelSmall)
            }
            if (open) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Transient confirmation: auto-dismisses. */
suspend fun SnackbarHostState.showInfo(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message, duration = SnackbarDuration.Short)
}

/** Transient error: stays until dismissed, so it cannot be missed. */
suspend fun SnackbarHostState.showError(message: String, closeLabel: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message, actionLabel = closeLabel, withDismissAction = false, duration = SnackbarDuration.Indefinite)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = snackbar ?: remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    // Edge-to-edge: system bars and the keyboard are both reserved (max per side, so the nav bar is not counted twice).
    Scaffold(
        snackbarHost = { SnackbarHost(host) },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            },
            actions = actions,
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad)
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } } // tap outside closes the keyboard
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Display name (Spanish) of a model class id from model.json. Unknown ids are shown as they are. */
fun displayClassName(raw: String): String = when (raw) {
    "green" -> "Verde"
    "semi_ripe" -> "Pintón"
    "ripe" -> "Maduro"
    "overripe" -> "Sobremaduro"
    "dry" -> "Seco"
    else -> raw
}

/** Class names plus, when they are generic, the string resource of the notice the screen must show. */
class ClassNames(val names: List<String>, @StringRes val noticeRes: Int?)

/** Class names from the model contract, or generic names (with a notice) when the model is unavailable or does not match. */
fun ServiceLocator.classNamesInfo(count: Int): ClassNames =
    try {
        val fromModel = modelSpec.classes.map(::displayClassName)
        if (fromModel.size == count) {
            ClassNames(fromModel, null)
        } else {
            ClassNames(List(count) { "Clase ${it + 1}" }, R.string.class_names_mismatch_notice)
        }
    } catch (e: ModelException) {
        Log.w(LOG_TAG, "Modelo no disponible; se usan nombres de clase genéricos", e)
        ClassNames(List(count) { "Clase ${it + 1}" }, R.string.class_names_unavailable_notice)
    }

/** Names only; screens that can show a notice should use [classNamesInfo]. */
fun ServiceLocator.classNames(count: Int): List<String> = classNamesInfo(count).names
