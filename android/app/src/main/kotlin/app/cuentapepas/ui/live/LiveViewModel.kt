package app.cuentapepas.ui.live

import android.app.Application
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.cuentapepas.CuentapepasApp
import app.cuentapepas.R
import app.cuentapepas.camera.CameraController
import app.cuentapepas.camera.RecordingEvent
import app.cuentapepas.camera.StopResult
import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.count.TrackCounter
import app.cuentapepas.core.live.PlantLog
import app.cuentapepas.core.live.SessionStatsAssembler
import app.cuentapepas.core.live.WarningTally
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.core.quality.GlobalMotionEstimator
import app.cuentapepas.core.quality.QualityAdvisor
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.session.Side
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.core.store.CorruptDataException
import app.cuentapepas.core.track.ByteTracker
import app.cuentapepas.device.DeviceMonitor
import app.cuentapepas.device.GpsState
import app.cuentapepas.device.LocationRecorder
import app.cuentapepas.inference.FallbackKind
import app.cuentapepas.inference.FramePreprocessor
import app.cuentapepas.inference.ModelInvalidException
import app.cuentapepas.inference.ModelLoadException
import app.cuentapepas.inference.ModelMissingException
import app.cuentapepas.inference.ModelOutputInvalidException
import app.cuentapepas.inference.TfliteDetector
import app.cuentapepas.pipeline.EvaluationLogger
import app.cuentapepas.pipeline.FrameProcessor
import app.cuentapepas.pipeline.FrameResult
import app.cuentapepas.pipeline.LiveDetectionSettings
import app.cuentapepas.pipeline.PlantSwitch
import app.cuentapepas.pipeline.ProcessorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt

/** Where the live screen is in its life. */
sealed interface LivePhase {
    /** Loading the session, model and detector. */
    data object Loading : LivePhase

    /** Camera can be bound and counting runs. */
    data class Ready(
        val classes: List<String>,
        val evaluationMode: Boolean,
        val row: Int,
        val side: Side,
        val minHits: Int,
        val minMeanScore: Float,
    ) : LivePhase

    /**
     * Counting is impossible ([message] is Spanish, ready to show; never a fake detection). [canAbandon] says whether
     * the session exists and can be closed (marked abandoned) from here.
     */
    data class Failed(val message: String, val canAbandon: Boolean) : LivePhase

    /** Saving and closing the session. The camera stays bound until the recording is stopped. */
    data object Finishing : LivePhase

    /**
     * Saving failed; the session is still ACTIVE. [LiveViewModel.finish] can be retried ("Reintentar") or the session
     * thrown away with [LiveViewModel.discardAndExit] ("Descartar y salir").
     */
    data class SaveFailed(val message: String) : LivePhase
}

/** Low-frequency state shown by the status bar, counts panel, warnings banner and notices. */
data class LiveHud(
    val plantNumber: Int? = null,
    /** Counts of the active plant; null ("—") until a frame was counted for it. */
    val plantCounts: ClassCounts? = null,
    /** Counts of the whole route; null ("—") until the first frame was processed. */
    val sessionCounts: ClassCounts? = null,
    val warnings: Set<QualityWarning> = emptySet(),
    /** Frames per second over the recent window; NaN = no data. */
    val fps: Float = Float.NaN,
    val delegate: String? = null,
    val thermal: Int = DeviceMonitor.NO_DATA,
    val batteryPct: Int? = null,
    val gps: GpsState = GpsState.Idle,
    /** Milliseconds since the last GPS fix; null if none yet. A fix older than [GPS_STALE_MS] is shown as lost. */
    val gpsFixAgeMs: Long? = null,
    /** GPS points recorded so far. */
    val gpsPoints: Int = 0,
    val paused: Boolean = false,
    /** True once the detector has processed at least one frame. */
    val hasProcessedFrames: Boolean = false,
    /** Informational and error lines (Spanish), shown small under the status bar. */
    val notices: List<String> = emptyList(),
) {
    companion object {
        /** A GPS fix older than this is no longer shown as "±x m". */
        const val GPS_STALE_MS = 5_000L
    }
}

/**
 * State and commands of the live screen. Owns the frame processor, camera controller, device monitor and location
 * recorder of one session; everything is released in [finish], [discardAndExit] or [onCleared].
 *
 * Counting accuracy rules: next-plant reads the closing plant's counts and switches the counter's plant in one step on
 * the processing thread ([FrameProcessor.switchPlant]), so no cherry is attributed to the wrong plant; commands are
 * serialised by a mutex. A plant that saw no processed frame is never saved (no ghost plants).
 */
class LiveViewModel(app: Application, private val sessionId: String) : AndroidViewModel(app) {
    private val services = (app as CuentapepasApp).services
    private val store = services.store

    val camera = CameraController(app)
    private val monitor = DeviceMonitor(app)
    private val location = LocationRecorder(app, viewModelScope, sink = { pts -> store.appendGps(sessionId, pts) })

    private val phaseFlow = MutableStateFlow<LivePhase>(LivePhase.Loading)
    val phase: StateFlow<LivePhase> = phaseFlow.asStateFlow()

    private val hudFlow = MutableStateFlow(LiveHud())
    val hud: StateFlow<LiveHud> = hudFlow.asStateFlow()

    private val frameFlow = MutableStateFlow<FrameResult?>(null)

    /** Latest frame result (every frame); read it from draw code only, never from composition. */
    val frame: StateFlow<FrameResult?> = frameFlow.asStateFlow()

    private val countedFlow = MutableStateFlow<Set<Int>>(emptySet())

    /** Ids of the tracks the counter has confirmed, refreshed whenever the session total changes. */
    val countedIds: StateFlow<Set<Int>> = countedFlow.asStateFlow()

    private val finishedFlow = MutableStateFlow<String?>(null)

    /** Set to the session id once it was saved and closed; the screen then navigates on. */
    val finished: StateFlow<String?> = finishedFlow.asStateFlow()

    private val leaveFlow = MutableStateFlow(false)

    /** True when the screen should simply navigate back (session discarded, or left from a failure panel). */
    val leave: StateFlow<Boolean> = leaveFlow.asStateFlow()

    private val commandMutex = Mutex()
    private val tally = WarningTally() // main thread only
    private var sticky: List<String> = emptyList()
    private var transient: List<String> = emptyList()
    private val videoIssues = ArrayList<String>()
    private var evalOpenError: String? = null

    private var meta: SessionMeta? = null
    private var processor: FrameProcessor? = null
    private var plantLog: PlantLog? = null
    private var liveStartMs = 0L
    private var batteryStartPct: Int? = null
    private var lastNextPlantAt = 0L
    private var recordingSegment = 0
    private var released = false

    init {
        viewModelScope.launch { setup() }
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                try {
                    tick()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Status refresh failed", e)
                    addSticky(str(R.string.live_tick_error, errText(e)))
                }
            }
        }
    }

    // ---- Helpers ----

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun fail(message: String, canAbandon: Boolean) {
        phaseFlow.value = LivePhase.Failed(message, canAbandon)
    }

    /** Spanish text of an exception: unwraps future wrappers, maps timeouts, falls back to the exception text. */
    private fun errText(e: Throwable): String {
        val t = if ((e is ExecutionException || e is CompletionException) && e.cause != null) e.cause!! else e
        return if (t is TimeoutException) str(R.string.live_processor_timeout) else t.message ?: t::class.java.simpleName
    }

    /** Waits for a processing-thread result off the main thread, never longer than [PROC_TIMEOUT_S]. */
    private suspend fun <T> CompletableFuture<T>.await(): T =
        withContext(Dispatchers.IO) { get(PROC_TIMEOUT_S, TimeUnit.SECONDS) }

    // ---- Setup ----

    private suspend fun setup() {
        var detector: TfliteDetector? = null // owned here until the FrameProcessor takes it over
        try {
            val loaded = try {
                store.session(sessionId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Cannot open session $sessionId", e)
                fail(str(R.string.live_session_error, e.message ?: e::class.java.simpleName), canAbandon = false)
                return
            }
            if (loaded.status != SessionStatus.ACTIVE) {
                fail(str(R.string.live_session_not_active), canAbandon = false)
                return
            }
            meta = loaded

            // Reading assets and settings is disk I/O: off the main thread.
            val (spec, settings) = withContext(Dispatchers.IO) {
                // The session's own snapshot rules, so a run is reproducible whatever the settings screen says today.
                val s = AppSettings.fromMap(loaded.settings, services.settings.load())
                services.modelSpec(s.detectionVariant) to s
            }
            detector = withContext(Dispatchers.Default) { services.detectorFactory(settings.detectionVariant, true) }

            var logger: EvaluationLogger? = null
            if (loaded.evaluationMode) {
                try {
                    logger = withContext(Dispatchers.IO) { EvaluationLogger(store.framesFile(sessionId)) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Cannot open the evaluation log", e)
                    val text = e.message ?: e::class.java.simpleName
                    evalOpenError = text
                    addSticky(str(R.string.live_eval_log_error, text))
                }
            }

            val proc = try {
                FrameProcessor(
                    detector = detector,
                    preprocessor = FramePreprocessor(spec),
                    settings = LiveDetectionSettings(settings.scoreThreshold, settings.nmsIou),
                    counter = TrackCounter(spec.numClasses, settings.minHits, settings.minMeanScore),
                    tracker = ByteTracker(settings.tracker),
                    advisor = QualityAdvisor(settings.quality),
                    motion = GlobalMotionEstimator(),
                    perf = PerfStats(),
                    logger = logger,
                    thermalStatus = { monitor.thermalStatus },
                )
            } catch (e: Exception) {
                try {
                    logger?.close()
                } catch (c: Exception) {
                    Log.e(TAG, "Cannot close the evaluation log", c)
                }
                throw e
            }
            detector = null // the processor owns it now and closes it
            processor = proc

            // Wait for the detector (bounded); frames before that would all be errors.
            val status = withTimeoutOrNull(MODEL_TIMEOUT_MS) { proc.status.first { it !is ProcessorStatus.Initializing } }
            if (status == null) {
                fail(str(R.string.live_model_timeout), canAbandon = true)
                return
            }
            if (status is ProcessorStatus.Failed) {
                Log.e(TAG, "Detector failed to initialise", status.error)
                fail(describeInitError(status.error), canAbandon = true)
                return
            }
            status as ProcessorStatus.Ready
            hudFlow.update { it.copy(delegate = status.delegate.name) }
            status.delegate.fallback?.let { fb ->
                addSticky(
                    when (fb.kind) {
                        FallbackKind.GPU_DISABLED_BY_CALLER -> str(R.string.delegate_fallback_disabled)
                        FallbackKind.GPU_NOT_SUPPORTED -> str(R.string.delegate_fallback_not_supported)
                        FallbackKind.GPU_INIT_FAILED -> str(R.string.delegate_fallback_init_failed, fb.detail ?: "?")
                    },
                )
            }

            val now = System.currentTimeMillis()
            liveStartMs = now
            val log = try {
                PlantLog(sessionId, loaded.firstPlant, loaded.plantStep, now)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid plant numbering in session $sessionId", e)
                fail(str(R.string.live_session_invalid, e.message ?: ""), canAbandon = true)
                return
            }
            plantLog = log
            proc.startPlant(log.currentPlant)
            batteryStartPct = monitor.batteryPct()

            viewModelScope.launch { collectResults(proc) }
            viewModelScope.launch { refreshCountedOnTotalChange(proc) }
            viewModelScope.launch {
                while (true) {
                    delay(AUTOSAVE_MS)
                    try {
                        saveProgress()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Autosave failed", e)
                        addSticky(str(R.string.live_autosave_error, errText(e)))
                    }
                }
            }

            phaseFlow.value = LivePhase.Ready(
                classes = spec.classes,
                evaluationMode = loaded.evaluationMode,
                row = loaded.row,
                side = loaded.side,
                minHits = settings.minHits,
                minMeanScore = settings.minMeanScore,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Live setup failed", e)
            fail(describeError(e), canAbandon = meta != null)
        } finally {
            detector?.let {
                try {
                    it.close() // never initialised here, so this is a plain release from any thread
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot close the detector after a failed setup", e)
                }
            }
        }
    }

    /** Typed, Spanish description of a setup failure. */
    private fun describeError(e: Throwable): String = when (e) {
        is ModelMissingException -> str(R.string.model_missing, e.assetPath)
        is ModelInvalidException -> str(R.string.model_invalid, e.message ?: "")
        is ModelLoadException -> str(R.string.model_load_failed, e.message ?: "")
        is ModelOutputInvalidException -> str(R.string.live_model_output_invalid, e.message ?: "")
        is CorruptDataException -> str(R.string.data_corrupt, e.path)
        else -> str(R.string.live_setup_error, "${e::class.java.simpleName}: ${e.message}")
    }

    /** Like [describeError] for the detector initialisation, where an unknown failure is a model-loading problem. */
    private fun describeInitError(e: Throwable): String = when (e) {
        is ModelMissingException, is ModelInvalidException, is ModelLoadException,
        is ModelOutputInvalidException, is CorruptDataException -> describeError(e)
        else -> str(R.string.model_load_failed, "${e::class.java.simpleName}: ${e.message}")
    }

    private suspend fun collectResults(proc: FrameProcessor) {
        proc.results.collect { r ->
            try {
                frameFlow.value = r
                if (r.frameIndex > 0) tally.add(r.frameIndex, r.warnings, r.paused)
                val processed = !r.medianInferenceMs.isNaN()
                hudFlow.update {
                    it.copy(
                        plantNumber = r.plantNumber,
                        // "No data" is shown as a dash, not as zero: nothing was counted yet for this plant / route.
                        plantCounts = r.currentPlantCounts.takeIf { r.plantCoverage.observed > 0 },
                        sessionCounts = r.sessionCounts.takeIf { processed },
                        warnings = r.warnings,
                        fps = if (r.fps.isNaN()) Float.NaN else (r.fps * 10f).roundToInt() / 10f,
                        delegate = r.delegate ?: it.delegate,
                        hasProcessedFrames = processed,
                        notices = buildNotices(r),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Cannot handle frame result", e)
                addSticky(str(R.string.live_results_error, errText(e)))
            }
        }
    }

    private suspend fun refreshCountedOnTotalChange(proc: FrameProcessor) {
        hudFlow.map { it.sessionCounts?.total ?: 0 }.distinctUntilChanged().collect {
            try {
                countedFlow.value = proc.withCounter { c -> c.countedTracks().mapTo(HashSet()) { t -> t.id } }.await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Normal while the processor shuts down; otherwise the overlay keeps the last known set and says so.
                Log.e(TAG, "Cannot refresh the counted tracks", e)
                if (phaseFlow.value is LivePhase.Ready) addSticky(str(R.string.live_results_error, errText(e)))
            }
        }
    }

    private fun buildNotices(r: FrameResult?): List<String> {
        val out = ArrayList<String>(sticky)
        out += transient
        if (r != null) {
            if (r.throttled) out += str(R.string.throttled_notice)
            if (r.errorCount > 0) out += str(R.string.frame_errors, r.errorCount, r.errorMessage ?: "")
            r.loggingError?.let { out += str(R.string.logging_error, it) }
        }
        location.sinkError?.let { out += str(R.string.live_gps_save_error, it) }
        return out
    }

    private fun refreshNotices() {
        hudFlow.update { it.copy(notices = buildNotices(frameFlow.value)) }
    }

    private fun addSticky(message: String) {
        if (message !in sticky) sticky = sticky + message
        refreshNotices()
    }

    private fun removeSticky(message: String) {
        sticky = sticky - message
        refreshNotices()
    }

    /** A notice that disappears by itself after [TRANSIENT_NOTICE_MS] (a refused command, not a standing problem). */
    private fun addTransient(message: String) {
        if (message !in transient) transient = transient + message
        refreshNotices()
        viewModelScope.launch {
            delay(TRANSIENT_NOTICE_MS)
            transient = transient - message
            refreshNotices()
        }
    }

    private fun tick() {
        hudFlow.update {
            it.copy(
                thermal = monitor.thermalStatus,
                batteryPct = monitor.batteryPct(),
                gps = location.state.value,
                gpsFixAgeMs = location.fixAgeMs(),
                gpsPoints = location.recordedCount,
                notices = buildNotices(frameFlow.value),
            )
        }
    }

    // ---- Camera ----

    /** Binds the camera for the screen's lifecycle; on failure the phase becomes [LivePhase.Failed]. */
    suspend fun bindCamera(owner: LifecycleOwner, previewView: PreviewView, rotation: Int) {
        val ready = phaseFlow.value as? LivePhase.Ready ?: return
        val proc = processor ?: return
        try {
            val outcome = camera.bind(owner, previewView, proc, proc.executor, ready.evaluationMode, rotation)
            if (outcome.videoUnavailable) {
                val cause = outcome.videoCause?.let { it.message ?: it::class.java.simpleName } ?: "?"
                videoIssues += str(R.string.live_video_unavailable_reason, cause)
                addSticky(str(R.string.live_video_unavailable_reason, cause))
            }
            if (outcome.videoBound) startNextRecording()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Cannot bind the camera", e)
            fail(str(R.string.live_camera_error, e.message ?: e::class.java.simpleName), canAbandon = true)
        }
    }

    /**
     * Unbinds the camera when the live content leaves the screen. While the session is being finished the camera stays
     * bound: [closeSession] stops the recording first and unbinds itself.
     */
    fun unbindCamera() {
        if (phaseFlow.value is LivePhase.Finishing) return
        camera.unbind()
    }

    private fun videoFailureLabel(code: Int): String = str(
        when (code) {
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> R.string.video_err_encoding
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> R.string.video_err_recorder
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> R.string.video_err_no_valid_data
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> R.string.video_err_invalid_output
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> R.string.video_err_duration_limit
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> R.string.video_err_storage
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> R.string.video_err_source_inactive
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> R.string.video_err_garbage_collected
            else -> R.string.video_err_unknown
        },
    )

    /** Starts the next video segment. Never throws: a failure is a sticky notice and counting goes on. */
    private fun startNextRecording() {
        try {
            val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (dir == null) {
                videoIssues += str(R.string.live_video_no_storage)
                addSticky(str(R.string.live_video_no_storage))
                return
            }
            val n = recordingSegment++
            val file = File(dir, if (n == 0) "$sessionId.mp4" else "${sessionId}_$n.mp4")
            val started = camera.startRecording(file) { event ->
                when (event) {
                    is RecordingEvent.Failed -> {
                        val text = str(R.string.live_video_failed_label, videoFailureLabel(event.code), event.code)
                        Log.e(TAG, "Video recording failed: $text")
                        videoIssues += text
                        addSticky(text)
                    }
                    is RecordingEvent.Interrupted -> addSticky(str(R.string.live_video_interrupted))
                    is RecordingEvent.Finished -> Unit
                }
            }
            // A new segment is recording again: the "interrupted" notice no longer applies.
            if (started) removeSticky(str(R.string.live_video_interrupted))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Cannot start the video recording", e)
            val text = str(R.string.live_video_start_failed, e.message ?: e::class.java.simpleName)
            videoIssues += text
            addSticky(text)
        }
    }

    // ---- Commands ----

    /**
     * Advances to the next plant. Ignored unless counting is ready; repeated presses within 700 ms count once.
     * Refused (with a Spanish notice, nothing changes) when the camera is not bound, when the current plant has not
     * seen a processed frame, or when a descending pass already is at plant 1.
     */
    fun nextPlant() {
        if (phaseFlow.value !is LivePhase.Ready) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNextPlantAt < NEXT_PLANT_DEBOUNCE_MS) return
        lastNextPlantAt = now
        viewModelScope.launch {
            commandMutex.withLock {
                val proc = processor ?: return@withLock
                val log = plantLog ?: return@withLock
                if (phaseFlow.value !is LivePhase.Ready) return@withLock
                if (!camera.isBound) {
                    addTransient(str(R.string.live_next_no_camera))
                    return@withLock
                }
                if (!log.canAdvance) {
                    addTransient(str(R.string.live_next_first_plant))
                    return@withLock
                }
                val closing = log.currentPlant
                try {
                    // Read the closing plant's counts and coverage and switch plants in ONE step on the processing thread.
                    when (val r = proc.switchPlant(log.nextPlant).await()) {
                        is PlantSwitch.NoFrames -> {
                            addTransient(str(R.string.live_next_no_frames))
                            return@withLock
                        }
                        is PlantSwitch.Switched -> {
                            check(r.closed.plantNumber == closing) {
                                "Plant mismatch: processor ${r.closed.plantNumber}, log $closing"
                            }
                            log.advance(r.closed.counts, r.closed.coverage, System.currentTimeMillis())
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Next plant failed", e)
                    addSticky(str(R.string.live_save_error, errText(e)))
                    return@withLock
                }
                saveProgressLocked()
            }
        }
    }

    fun setPaused(paused: Boolean) {
        processor?.paused = paused
        hudFlow.update { it.copy(paused = paused) }
        if (!paused) removeSticky(str(R.string.live_background_paused))
    }

    /** The app left the foreground: pause counting and persist what exists. The session stays ACTIVE. */
    fun onBackground() {
        if (phaseFlow.value !is LivePhase.Ready) return
        setPaused(true)
        addSticky(str(R.string.live_background_paused))
        viewModelScope.launch {
            commandMutex.withLock { saveProgressLocked() }
            location.flush()
        }
    }

    /** The app is visible again: the evaluation video (stopped with the camera) starts a new segment. */
    fun onForeground() {
        if (phaseFlow.value is LivePhase.Ready && camera.isBound && camera.canRecord && !camera.isRecording) {
            startNextRecording()
        }
    }

    fun startLocation() {
        if (phaseFlow.value is LivePhase.Ready) location.start()
    }

    private suspend fun saveProgress() {
        if (phaseFlow.value !is LivePhase.Ready) return
        commandMutex.withLock { saveProgressLocked() }
    }

    /**
     * Persists plants (current one still open, only plants that saw frames) and pending GPS. Call with
     * [commandMutex] held. Returns false (and shows a notice) when saving failed.
     */
    private suspend fun saveProgressLocked(): Boolean {
        val proc = processor ?: return true
        val log = plantLog ?: return true
        return try {
            val snap = proc.snapshotPlant().await()
            check(snap.plantNumber == log.currentPlant) { "Plant mismatch: processor ${snap.plantNumber}, log ${log.currentPlant}" }
            store.savePlants(
                sessionId,
                log.records(snap.counts, snap.coverage, System.currentTimeMillis(), closeCurrent = false),
            )
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Saving progress failed", e)
            addSticky(str(R.string.live_save_error, errText(e)))
            false
        }
    }

    /**
     * Ends the session: stops recording, camera and GPS, saves the last plant and the session stats, then marks the
     * session COMPLETED (or ABANDONED when no plant has data, so an empty run never replaces an earlier pass) and
     * sets [finished]. If saving fails the phase becomes [LivePhase.SaveFailed] and this can be called again.
     */
    fun finish() {
        if (phaseFlow.value is LivePhase.Finishing) return
        viewModelScope.launch {
            commandMutex.withLock {
                if (phaseFlow.value is LivePhase.Finishing || finishedFlow.value != null) return@withLock
                phaseFlow.value = LivePhase.Finishing
                try {
                    closeSession()
                    finishedFlow.value = sessionId
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Finishing the session failed", e)
                    camera.unbind() // the recording is stopped (or failed to); nothing more to keep bound
                    phaseFlow.value = LivePhase.SaveFailed(str(R.string.live_save_error, errText(e)))
                }
            }
        }
    }

    /**
     * "Descartar y salir": marks the session ABANDONED through the store, releases everything and asks the screen to
     * navigate back. On a store failure the phase becomes [LivePhase.SaveFailed] again with the reason.
     */
    fun discardAndExit() {
        if (phaseFlow.value is LivePhase.Finishing) return
        viewModelScope.launch {
            commandMutex.withLock {
                if (phaseFlow.value is LivePhase.Finishing || finishedFlow.value != null) return@withLock
                val previous = phaseFlow.value
                phaseFlow.value = LivePhase.Finishing
                try {
                    try {
                        camera.stopRecording()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Stopping the video while discarding failed", e)
                    }
                    camera.unbind()
                    location.stop()
                    val loaded = meta
                    if (loaded != null) {
                        store.updateSession(loaded.copy(status = SessionStatus.ABANDONED, endedAtMs = System.currentTimeMillis()))
                    }
                    releaseResources()
                    leaveFlow.value = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Discarding the session failed", e)
                    phaseFlow.value = if (previous is LivePhase.SaveFailed || previous is LivePhase.Failed) {
                        LivePhase.SaveFailed(str(R.string.live_discard_error, errText(e)))
                    } else {
                        previous
                    }
                }
            }
        }
    }

    /**
     * Leaves from a failure panel without closing the session: unsaved progress is saved first and the screen only
     * navigates back when that worked (the session stays ACTIVE; the next start marks it ABANDONED).
     */
    fun leaveSaving() {
        val previous = phaseFlow.value as? LivePhase.Failed ?: return
        viewModelScope.launch {
            commandMutex.withLock {
                if (plantLog != null && !saveProgressLocked()) {
                    fail(previous.message + "\n" + str(R.string.live_leave_save_error, sticky.lastOrNull() ?: "?"), previous.canAbandon)
                    return@withLock
                }
                leaveFlow.value = true
            }
        }
    }

    private suspend fun closeSession() {
        val loaded = meta
        val proc = processor
        val log = plantLog
        val now = System.currentTimeMillis()

        // The camera stays bound until the recording is stopped and its file finalised.
        if (camera.stopRecording() == StopResult.TIMED_OUT) {
            val text = str(R.string.live_video_finalize_timeout)
            videoIssues += text
            addSticky(text)
        }
        camera.unbind() // no more frames reach the processor
        location.stop()
        location.flush()

        var frames = 0
        var hasPlants = false
        if (proc != null && log != null && loaded != null) {
            proc.paused = true
            val snap = proc.snapshotPlant().await()
            check(snap.plantNumber == log.currentPlant) { "Plant mismatch: processor ${snap.plantNumber}, log ${log.currentPlant}" }
            val durationS = (now - liveStartMs) / 1000.0
            val delegate = frameFlow.value?.delegate ?: hudFlow.value.delegate
            val fractions = tally.fractions() // main thread: the tally is not thread-safe
            val totals = proc.totals().await()
            // Read device state here, not on the processing thread.
            val maxThermal = monitor.maxThermalSeen
            val batteryEnd = monitor.batteryPct()
            val gpsPoints = location.points
            val gpsFailure = location.lastSinkFailure?.let { "${location.sinkFailures} intento(s) fallaron; último: $it" }
            val videoIssue = videoIssues.takeIf { it.isNotEmpty() }?.joinToString("; ")
            val stats = proc.withPerf { perf ->
                SessionStatsAssembler.build(
                    perf = perf,
                    durationS = durationS,
                    delegate = delegate,
                    maxThermalStatus = maxThermal,
                    batteryStartPct = batteryStartPct,
                    batteryEndPct = batteryEnd,
                    warningFractions = fractions,
                    gps = gpsPoints,
                    framesErrored = totals.framesErrored,
                    framesDroppedByThrottle = totals.framesDroppedByThrottle,
                    evalLogError = totals.loggingError ?: evalOpenError,
                    gpsSaveError = gpsFailure,
                    videoIssue = videoIssue,
                )
            }.await()
            frames = stats.framesProcessed
            if (frames > 0) {
                // Points that could not be saved are an error, never silently dropped: SaveFailed lets the user retry.
                if (location.unsavedCount > 0 || location.sinkError != null) {
                    throw IOException(
                        str(R.string.live_gps_finish_error, location.unsavedCount, location.sinkError ?: "?"),
                    )
                }
                val records = log.records(snap.counts, snap.coverage, now, closeCurrent = true)
                hasPlants = records.isNotEmpty()
                store.savePlants(sessionId, records)
                store.saveStats(sessionId, stats)
            }
            // frames == 0: nothing was processed, so no plants and no stats are saved at all.
        }
        if (loaded != null) {
            if (hasPlants) {
                store.completeSession(sessionId, now)
            } else {
                store.updateSession(loaded.copy(status = SessionStatus.ABANDONED, endedAtMs = now))
            }
        }
        releaseResources()
    }

    private suspend fun releaseResources() {
        if (released) return
        released = true
        val proc = processor
        monitor.close()
        if (proc != null) {
            val failure = withContext(Dispatchers.IO) {
                try {
                    proc.close()
                    null
                } catch (e: Exception) {
                    // The data is already saved; a slow or failing detector release must not block leaving.
                    Log.e(TAG, "Releasing the frame processor failed", e)
                    e
                }
            }
            if (failure != null) addSticky(str(R.string.live_release_error, errText(failure)))
        }
    }

    override fun onCleared() {
        if (!released) {
            released = true
            camera.unbind()
            location.stop()
            monitor.close()
            val proc = processor
            if (proc != null) {
                // close() waits for the processing thread; never block the main thread on it.
                Thread({
                    try {
                        proc.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Releasing the frame processor failed", e)
                    }
                }, "frame-processor-release").start()
            }
        }
        super.onCleared()
    }

    class Factory(private val app: Application, private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveViewModel(app, sessionId) as T
    }

    private companion object {
        const val TAG = "Cuentapepas"
        const val TICK_MS = 1_000L
        const val AUTOSAVE_MS = 20_000L
        const val NEXT_PLANT_DEBOUNCE_MS = 700L
        const val TRANSIENT_NOTICE_MS = 4_000L
        const val MODEL_TIMEOUT_MS = 30_000L
        const val PROC_TIMEOUT_S = 10L
    }
}
