package app.pacewise.shared

import kotlin.math.roundToInt

enum class Cue { SPEED_UP, SLOW_DOWN, ON_TARGET, NONE }

enum class CueBasis { PACE, HEART_RATE }

data class Guidance(
    val cue: Cue,
    /** How far outside the target: seconds per km for pace, beats for heart rate. */
    val delta: Int = 0,
    val basis: CueBasis = CueBasis.PACE,
)

/** Decides whether the runner should speed up, slow down or hold. */
object PaceCoach {
    fun evaluate(target: Target?, paceSecPerKm: Double?, hr: Int?): Guidance {
        if (target == null) return Guidance(Cue.NONE)

        // A heart-rate ceiling always wins: easy days must stay easy.
        if (hr != null && target.hrMax != null && hr > target.hrMax) {
            return Guidance(Cue.SLOW_DOWN, hr - target.hrMax, CueBasis.HEART_RATE)
        }
        if (target.hasPace) {
            val pace = paceSecPerKm ?: return Guidance(Cue.NONE)
            return when {
                pace > target.paceSlow!! -> Guidance(Cue.SPEED_UP, (pace - target.paceSlow).roundToInt())
                pace < target.paceFast!! -> Guidance(Cue.SLOW_DOWN, (target.paceFast - pace).roundToInt())
                else -> Guidance(Cue.ON_TARGET)
            }
        }
        if (target.hasHr) {
            if (hr == null) return Guidance(Cue.NONE, basis = CueBasis.HEART_RATE)
            val min = target.hrMin
            return if (min != null && hr < min) Guidance(Cue.SPEED_UP, min - hr, CueBasis.HEART_RATE)
            else Guidance(Cue.ON_TARGET, basis = CueBasis.HEART_RATE)
        }
        return Guidance(Cue.NONE)
    }
}

/**
 * Turns the cumulative GPS distance into a stable current pace by looking at the distance
 * covered over the last [windowS] seconds of active time.
 */
class PaceSmoother(private val windowS: Int = 20, private val minSpanS: Int = 6) {
    private val points = ArrayDeque<Pair<Double, Double>>() // active seconds, metres

    fun add(activeS: Double, distanceM: Double) {
        if (points.isNotEmpty() && activeS <= points.last().first) return
        points.addLast(activeS to distanceM)
        while (points.size > 2 && activeS - points.first().first > windowS) points.removeFirst()
    }

    fun reset() = points.clear()

    /** Seconds per km, or null while standing still or without enough data. */
    fun pace(): Double? {
        if (points.size < 2) return null
        val (t0, d0) = points.first()
        val (t1, d1) = points.last()
        val dt = t1 - t0
        val dd = d1 - d0
        if (dt < minSpanS || dd < 0.5 * dt) return null // slower than 0.5 m/s: treat as stopped
        return dt / dd * 1000.0
    }
}

enum class Segment { RUN, WALK }

data class EngineState(
    val stepIndex: Int,
    val stepCount: Int,
    val step: Step?,
    val nextStep: Step?,
    /** 0–1 through the current step. */
    val stepProgress: Float,
    val stepRemainingM: Int?,
    val stepRemainingS: Int?,
    /** 0–1 through the whole workout (by planned distance). */
    val totalProgress: Float,
    val finished: Boolean,
    /** Present only on run-walk steps. */
    val segment: Segment? = null,
    val segmentRemainingS: Int? = null,
) {
    /** The target that applies right now (none during walk breaks). */
    val activeTarget: Target? get() = if (segment == Segment.WALK) null else step?.target
}

/**
 * Walks through the steps of a workout as distance and active time accumulate. Pure logic,
 * so the watch service just feeds it numbers.
 */
class WorkoutEngine(val workout: Workout?) {
    private val steps = workout?.steps.orEmpty()
    var index = 0
        private set
    private var stepStartM = 0.0
    private var stepStartS = 0.0
    private var lastM = 0.0
    private var lastS = 0.0
    val completed = ArrayList<CompletedStep>()

    data class CompletedStep(val step: Step, val distanceM: Double, val durationS: Double, val startS: Double)

    val finished: Boolean get() = steps.isNotEmpty() && index >= steps.size

    fun update(distanceM: Double, activeS: Double): EngineState {
        lastM = distanceM
        lastS = activeS
        while (index < steps.size) {
            val s = steps[index]
            val done = when {
                s.distanceM != null -> distanceM - stepStartM >= s.distanceM
                s.durationS != null -> activeS - stepStartS >= s.durationS
                else -> false
            }
            if (!done) break
            // Carry the overshoot into the next step so totals stay exact.
            val endM = if (s.distanceM != null) stepStartM + s.distanceM else distanceM
            val endS = if (s.durationS != null) stepStartS + s.durationS else activeS
            advance(endM, endS)
        }
        return state()
    }

    /** Manually end the current step now (e.g. the track is busy or the hill is shorter). */
    fun skip(): EngineState {
        if (index < steps.size) advance(lastM, lastS)
        return state()
    }

    private fun advance(endM: Double, endS: Double) {
        completed += CompletedStep(steps[index], endM - stepStartM, endS - stepStartS, stepStartS)
        stepStartM = endM
        stepStartS = endS
        index++
    }

    fun state(): EngineState {
        if (steps.isEmpty()) return EngineState(0, 0, null, null, 0f, null, null, 0f, false)
        if (index >= steps.size) return EngineState(steps.size, steps.size, null, null, 1f, null, null, 1f, true)
        val s = steps[index]
        val doneM = (lastM - stepStartM).coerceAtLeast(0.0)
        val doneS = (lastS - stepStartS).coerceAtLeast(0.0)
        val progress: Float
        var remM: Int? = null
        var remS: Int? = null
        if (s.distanceM != null) {
            progress = (doneM / s.distanceM).toFloat()
            remM = (s.distanceM - doneM).roundToInt().coerceAtLeast(0)
        } else {
            val dur = s.durationS ?: 1
            progress = (doneS / dur).toFloat()
            remS = (dur - doneS).roundToInt().coerceAtLeast(0)
        }
        var segment: Segment? = null
        var segRem: Int? = null
        s.runWalk?.let { rw ->
            val cycle = rw.runS + rw.walkS
            val pos = doneS % cycle
            if (pos < rw.runS) { segment = Segment.RUN; segRem = (rw.runS - pos).roundToInt() }
            else { segment = Segment.WALK; segRem = (cycle - pos).roundToInt() }
        }
        val total = (index + progress.coerceIn(0f, 1f)) / steps.size
        val planned = workout?.plannedDistanceM ?: 0
        val totalProgress = if (planned > 0) (lastM / planned).toFloat().coerceIn(0f, 1f) else total
        return EngineState(
            index, steps.size, s, steps.getOrNull(index + 1), progress.coerceIn(0f, 1f), remM, remS,
            totalProgress, false, segment, segRem,
        )
    }
}
