package app.pacewise.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.pacewise.shared.Cue
import app.pacewise.shared.EngineState
import app.pacewise.shared.Guidance
import app.pacewise.shared.PaceCoach
import app.pacewise.shared.PaceSmoother
import app.pacewise.shared.RunRecord
import app.pacewise.shared.RunSource
import app.pacewise.shared.Sample
import app.pacewise.shared.Segment
import app.pacewise.shared.StepResult
import app.pacewise.shared.SyncProtocol
import app.pacewise.shared.WatchSettings
import app.pacewise.shared.Workout
import app.pacewise.shared.WorkoutEngine
import app.pacewise.shared.WorkoutType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

enum class RunPhase { IDLE, STARTING, ACTIVE, PAUSED, FINISHED }

data class RunUi(
    val phase: RunPhase = RunPhase.IDLE,
    val workout: Workout? = null,
    val activeS: Long = 0,
    val distanceM: Double = 0.0,
    /** smoothed current pace, s/km */
    val pace: Double? = null,
    val hr: Int? = null,
    val cadence: Int? = null,
    val calories: Int? = null,
    val elevGainM: Int? = null,
    val gpsReady: Boolean = false,
    val autoPaused: Boolean = false,
    val engine: EngineState = WorkoutEngine(null).state(),
    val guidance: Guidance = Guidance(Cue.NONE),
    val summary: RunRecord? = null,
    val message: String? = null,
) {
    val avgPace: Double? get() = if (distanceM > 50) activeS / (distanceM / 1000.0) else null
    val running: Boolean get() = phase == RunPhase.STARTING || phase == RunPhase.ACTIVE || phase == RunPhase.PAUSED
}

/** Live state of the current run, observed by the UI. */
object RunSession {
    private val _ui = MutableStateFlow(RunUi())
    val ui: StateFlow<RunUi> = _ui
    internal fun set(v: RunUi) { _ui.value = v }
    fun clearSummary() { if (!_ui.value.running) _ui.value = RunUi() }
}

/**
 * Records the run with Health Services (GPS distance, pace, heart rate, cadence, calories,
 * elevation), drives the workout steps and tells the runner when to speed up or slow down.
 * Runs as a foreground service so it keeps going with the screen off.
 */
class ExerciseService : LifecycleService() {
    private lateinit var client: ExerciseClient
    private var ticker: Job? = null

    private var engine = WorkoutEngine(null)
    private val smoother = PaceSmoother()
    private var settings = WatchSettings()
    private var workout: Workout? = null
    private var startEpochMs = 0L

    // latest sensor values
    private var hsState: ExerciseState? = null
    private var checkpointTime: Instant? = null
    private var checkpointActive: Duration = Duration.ZERO
    private var distanceM = 0.0
    private var speed: Double? = null
    private var hr: Int? = null
    private var maxHr = 0
    private var hrSum = 0L
    private var hrN = 0
    private var cadence: Int? = null
    private var cadSum = 0L
    private var cadN = 0
    private var steps: Long? = null
    private var calories: Double? = null
    private var elevGain: Double? = null
    private var lat: Double? = null
    private var lon: Double? = null
    private var alt: Double? = null
    private var gpsReady = false

    // coaching bookkeeping
    private val samples = ArrayList<Sample>()
    private var lastSampleS = -100L
    private var lastStepIndex = 0
    private var lastSegment: Segment? = null
    private var stepChangedAtS = 0L
    private var stepHrSum = 0L
    private var stepHrN = 0
    private val stepHr = ArrayList<Int?>()
    private var lastCueAtS = -1000L
    private var lastCue = Cue.NONE
    private var coachedS = 0
    private var onTargetS = 0
    private var announcedFinish = false

    override fun onCreate() {
        super.onCreate()
        client = HealthServices.getClient(this).exerciseClient
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_WORKOUT))
            ACTION_PAUSE -> lifecycleScope.launch { runCatching { client.pauseExerciseAsync().await() } }
            ACTION_RESUME -> lifecycleScope.launch { runCatching { client.resumeExerciseAsync().await() } }
            ACTION_SKIP -> { engine.skip(); tick() }
            ACTION_STOP -> finish(save = true)
            ACTION_DISCARD -> finish(save = false)
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun start(workoutJson: String?) {
        if (RunSession.ui.value.running) return
        workout = workoutJson?.let { runCatching { SyncProtocol.json.decodeFromString(Workout.serializer(), it) }.getOrNull() }
        settings = store.payload.value.settings
        engine = WorkoutEngine(workout)
        startEpochMs = System.currentTimeMillis()
        RunSession.set(RunUi(phase = RunPhase.STARTING, workout = workout, engine = engine.state()))

        val hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasBody = granted(Manifest.permission.BODY_SENSORS) || granted("android.permission.health.READ_HEART_RATE")
        val hasActivity = granted(Manifest.permission.ACTIVITY_RECOGNITION)
        try {
            // The "health" service type only exists from Wear OS 5 (API 34).
            var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
            if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (type != 0) startForeground(NOTIFICATION_ID, buildNotification(), type)
            else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            fail("Allow sensor access to record a run.", e)
            return
        }

        lifecycleScope.launch {
            try {
                val caps = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(ExerciseType.RUNNING)
                val wanted = buildSet<DataType<*, *>> {
                    if (hasActivity) {
                        add(DataType.DISTANCE_TOTAL); add(DataType.SPEED); add(DataType.STEPS_TOTAL)
                        add(DataType.STEPS_PER_MINUTE); add(DataType.CALORIES_TOTAL)
                        add(DataType.ELEVATION_GAIN_TOTAL)
                    }
                    if (hasBody) add(DataType.HEART_RATE_BPM)
                    if (hasLocation) { add(DataType.LOCATION); add(DataType.ABSOLUTE_ELEVATION) }
                }.filter { it in caps.supportedDataTypes }.toSet()
                val config = ExerciseConfig.builder(ExerciseType.RUNNING)
                    .setDataTypes(wanted)
                    .setIsGpsEnabled(hasLocation && DataType.LOCATION in wanted)
                    .setIsAutoPauseAndResumeEnabled(settings.autoPause && caps.supportsAutoPauseAndResume)
                    .build()
                client.setUpdateCallback(callback)
                client.startExerciseAsync(config).await()
                buzz(START_PATTERN)
                startTicker()
            } catch (e: Exception) {
                fail("Couldn't start recording. Check permissions and try again.", e)
            }
        }
    }

    private fun fail(message: String, e: Exception) {
        Log.e(TAG, message, e)
        RunSession.set(RunUi(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val m = update.latestMetrics
            m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.roundToInt()?.takeIf { it > 0 }?.let {
                hr = it
                if (it > maxHr) maxHr = it
            }
            m.getData(DataType.DISTANCE_TOTAL)?.let { distanceM = it.total }
            m.getData(DataType.SPEED).lastOrNull()?.let { speed = it.value }
            m.getData(DataType.STEPS_PER_MINUTE).lastOrNull()?.let { cadence = it.value.toInt() }
            m.getData(DataType.STEPS_TOTAL)?.let { steps = it.total }
            m.getData(DataType.CALORIES_TOTAL)?.let { calories = it.total }
            m.getData(DataType.ELEVATION_GAIN_TOTAL)?.let { elevGain = it.total }
            m.getData(DataType.LOCATION).lastOrNull()?.value?.let {
                lat = it.latitude; lon = it.longitude
                if (!it.altitude.isNaN()) alt = it.altitude
            }
            m.getData(DataType.ABSOLUTE_ELEVATION).lastOrNull()?.let { alt = it.value }
            update.activeDurationCheckpoint?.let {
                checkpointTime = it.time
                checkpointActive = it.activeDuration
            }
            hsState = update.exerciseStateInfo.state
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) { Log.e(TAG, "Callback registration failed", throwable) }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (availability is LocationAvailability) {
                gpsReady = availability == LocationAvailability.ACQUIRED_TETHERED || availability == LocationAvailability.ACQUIRED_UNTETHERED
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    private fun activeSeconds(): Long {
        val state = hsState
        val t = checkpointTime
        val base = checkpointActive
        return if (state == ExerciseState.ACTIVE && t != null) (base + Duration.between(t, Instant.now())).seconds.coerceAtLeast(0)
        else base.seconds
    }

    /** One coaching cycle: runs every second. */
    private fun tick() {
        val state = hsState ?: run {
            RunSession.set(RunSession.ui.value.copy(gpsReady = gpsReady))
            return
        }
        if (state.isEnded) return
        val active = state == ExerciseState.ACTIVE
        val activeS = activeSeconds()

        var pace: Double? = null
        if (active) {
            smoother.add(activeS.toDouble(), distanceM)
            pace = smoother.pace() ?: speed?.takeIf { it > 0.6 }?.let { 1000.0 / it }
        } else smoother.reset()

        val es = engine.update(distanceM, activeS.toDouble())

        // step and run/walk transitions
        if (es.stepIndex != lastStepIndex) {
            while (stepHr.size < engine.completed.size) stepHr += if (stepHrN > 0) (stepHrSum / stepHrN).toInt() else null
            stepHrSum = 0; stepHrN = 0
            lastStepIndex = es.stepIndex
            stepChangedAtS = activeS
            lastSegment = es.segment
            smoother.reset()
            if (!es.finished) buzz(STEP_PATTERN)
        } else if (es.segment != lastSegment) {
            lastSegment = es.segment
            stepChangedAtS = activeS
            smoother.reset()
            buzz(if (es.segment == Segment.WALK) SLOW_PATTERN else FAST_PATTERN)
        }
        if (es.finished && !announcedFinish) {
            announcedFinish = true
            buzz(DONE_PATTERN)
        }

        // guidance, with a short settling time after each change of target
        var guidance = Guidance(Cue.NONE)
        if (active && activeS - stepChangedAtS >= SETTLE_S) {
            guidance = PaceCoach.evaluate(es.activeTarget, pace, hr)
            if (guidance.cue != Cue.NONE) {
                coachedS++
                if (guidance.cue == Cue.ON_TARGET) onTargetS++
            }
            val off = guidance.cue == Cue.SPEED_UP || guidance.cue == Cue.SLOW_DOWN
            if (settings.vibrateCues) {
                if (off && activeS - lastCueAtS >= settings.cueIntervalS) {
                    buzz(if (guidance.cue == Cue.SPEED_UP) FAST_PATTERN else SLOW_PATTERN)
                    lastCueAtS = activeS
                } else if (guidance.cue == Cue.ON_TARGET && (lastCue == Cue.SPEED_UP || lastCue == Cue.SLOW_DOWN)) {
                    buzz(OK_PATTERN)
                }
            }
            lastCue = guidance.cue
        }

        if (active) {
            hr?.let { hrSum += it; hrN++; stepHrSum += it; stepHrN++ }
            cadence?.takeIf { it > 0 }?.let { cadSum += it; cadN++ }
            if (activeS - lastSampleS >= SAMPLE_EVERY_S) {
                lastSampleS = activeS
                samples += Sample(
                    t = activeS.toInt(), d = distanceM.toFloat(), hr = hr, pace = pace?.toFloat(),
                    lat = lat, lon = lon, alt = alt?.toFloat(), cad = cadence?.takeIf { it > 0 },
                )
            }
        }

        RunSession.set(
            RunUi(
                phase = if (active) RunPhase.ACTIVE else RunPhase.PAUSED,
                workout = workout, activeS = activeS, distanceM = distanceM, pace = pace, hr = hr,
                cadence = cadence?.takeIf { it > 0 }, calories = calories?.roundToInt(), elevGainM = elevGain?.roundToInt(),
                gpsReady = gpsReady, autoPaused = state == ExerciseState.AUTO_PAUSED || state == ExerciseState.AUTO_PAUSING,
                engine = es, guidance = guidance,
            ),
        )
    }

    private fun finish(save: Boolean) {
        ticker?.cancel()
        val activeS = activeSeconds()
        lifecycleScope.launch {
            runCatching { client.endExerciseAsync().await() }
            runCatching { client.clearUpdateCallbackAsync(callback).await() }
            val keep = save && (distanceM >= 50 || activeS >= 60)
            var record: RunRecord? = null
            if (keep) {
                engine.skip() // close the step in progress so it is reported too
                while (stepHr.size < engine.completed.size) stepHr += if (stepHrN > 0) (stepHrSum / stepHrN).toInt() else null
                val w = workout
                record = RunRecord(
                    id = "run-$startEpochMs", startEpochMs = startEpochMs, durationS = activeS.toInt(),
                    elapsedS = ((System.currentTimeMillis() - startEpochMs) / 1000).toInt(),
                    distanceM = distanceM.roundToInt(),
                    avgHr = if (hrN > 0) (hrSum / hrN).toInt() else null, maxHr = maxHr.takeIf { it > 0 },
                    calories = calories?.roundToInt(), elevGainM = elevGain?.roundToInt(),
                    avgCadence = if (cadN > 0) (cadSum / cadN).toInt() else null, steps = steps?.toInt(),
                    workoutId = w?.id, title = w?.title ?: "Free run", type = w?.type ?: WorkoutType.FREE,
                    source = RunSource.WATCH,
                    onTargetPct = if (coachedS >= 60) onTargetS * 100 / coachedS else null,
                    stepResults = if (w == null) emptyList() else engine.completed.mapIndexedNotNull { i, c ->
                        if (c.durationS < 1) null
                        else StepResult(c.step.label, c.step.kind, c.distanceM.roundToInt(), c.durationS.roundToInt(), stepHr.getOrNull(i), c.step.target)
                    },
                    samples = samples.toList(),
                )
                store.addPending(record)
                WearSync.uploadPending(this@ExerciseService)
            }
            RunSession.set(
                if (record != null) RunUi(phase = RunPhase.FINISHED, workout = workout, summary = record)
                else RunUi(message = if (save) "Too short to save." else null),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buzz(pattern: LongArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Run in progress", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PaceWise")
            .setContentText(workout?.title ?: "Free run")
            .setSmallIcon(R.drawable.ic_run)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        // Shows the run on the watch face and in recents so one tap returns to it.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_run)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Run in progress").build())
            .build()
            .apply(applicationContext)
        return builder.build()
    }

    companion object {
        private const val TAG = "PaceWiseRun"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_EVERY_S = 5
        private const val SETTLE_S = 12

        const val ACTION_START = "app.pacewise.START"
        const val ACTION_PAUSE = "app.pacewise.PAUSE"
        const val ACTION_RESUME = "app.pacewise.RESUME"
        const val ACTION_SKIP = "app.pacewise.SKIP"
        const val ACTION_STOP = "app.pacewise.STOP"
        const val ACTION_DISCARD = "app.pacewise.DISCARD"
        const val EXTRA_WORKOUT = "workout"

        // Distinct patterns you can tell apart without looking.
        private val FAST_PATTERN = longArrayOf(0, 90, 80, 90)          // two short: speed up / run
        private val SLOW_PATTERN = longArrayOf(0, 450)                 // one long: slow down / walk
        private val OK_PATTERN = longArrayOf(0, 40)                    // tick: back on target
        private val STEP_PATTERN = longArrayOf(0, 120, 90, 120, 90, 120) // three: next step
        private val START_PATTERN = longArrayOf(0, 200)
        private val DONE_PATTERN = longArrayOf(0, 300, 120, 300, 120, 600)

        fun send(context: Context, action: String, workout: Workout? = null) {
            val intent = Intent(context, ExerciseService::class.java).setAction(action)
            if (workout != null) intent.putExtra(EXTRA_WORKOUT, SyncProtocol.json.encodeToString(Workout.serializer(), workout))
            if (action == ACTION_START) context.startForegroundService(intent)
            else if (RunSession.ui.value.running) context.startService(intent)
        }
    }
}
