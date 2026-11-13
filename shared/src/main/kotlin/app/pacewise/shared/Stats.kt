package app.pacewise.shared

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class WeekBucket(val start: LocalDate, val distanceM: Int, val plannedM: Int, val runs: Int, val durationS: Int)

data class Adherence(val done: Int, val skipped: Int, val missed: Int) {
    val total get() = done + skipped + missed
    val percent get() = if (total == 0) 100 else done * 100 / total
}

enum class WorkoutStatus { DONE, SKIPPED, MISSED, TODAY, UPCOMING, BEFORE_START }

object Stats {
    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun runFor(w: Workout, runs: List<RunRecord>): RunRecord? =
        runs.firstOrNull { it.workoutId == w.id } ?: if (w.type.isRun) runs.firstOrNull { it.localDate.toString() == w.date } else null

    fun status(
        w: Workout, plan: TrainingPlan, runs: List<RunRecord>, completions: Map<String, Completion>, today: LocalDate,
    ): WorkoutStatus {
        when (completions[w.id]) {
            Completion.DONE -> return WorkoutStatus.DONE
            Completion.SKIPPED -> return WorkoutStatus.SKIPPED
            null -> {}
        }
        if (runFor(w, runs) != null) return WorkoutStatus.DONE
        val d = w.localDate
        return when {
            d == today -> WorkoutStatus.TODAY
            d.isAfter(today) -> WorkoutStatus.UPCOMING
            d.isBefore(LocalDate.parse(plan.createdDate)) -> WorkoutStatus.BEFORE_START
            else -> WorkoutStatus.MISSED
        }
    }

    fun adherence(plan: TrainingPlan, runs: List<RunRecord>, completions: Map<String, Completion>, today: LocalDate): Adherence {
        var done = 0; var skipped = 0; var missed = 0
        for (w in plan.allWorkouts()) when (status(w, plan, runs, completions, today)) {
            WorkoutStatus.DONE -> done++
            WorkoutStatus.SKIPPED -> skipped++
            WorkoutStatus.MISSED -> missed++
            else -> {}
        }
        return Adherence(done, skipped, missed)
    }

    /** The last [count] calendar weeks, oldest first, ending with the current week. */
    fun weekly(runs: List<RunRecord>, plan: TrainingPlan?, today: LocalDate, count: Int = 12): List<WeekBucket> {
        val thisWeek = monday(today)
        val byWeek = runs.groupBy { monday(it.localDate) }
        val planned = plan?.weeks?.associate { LocalDate.parse(it.startDate) to it.plannedDistanceM }.orEmpty()
        return (count - 1 downTo 0).map { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            val rs = byWeek[start].orEmpty()
            WeekBucket(start, rs.sumOf { it.distanceM }, planned[start] ?: 0, rs.size, rs.sumOf { it.durationS })
        }
    }

    /** Consecutive weeks (ending this or last week) with at least one run. */
    fun weekStreak(runs: List<RunRecord>, today: LocalDate): Int {
        val weeks = runs.map { monday(it.localDate) }.toSet()
        var w = monday(today)
        if (w !in weeks) w = w.minusWeeks(1)
        var n = 0
        while (w in weeks) { n++; w = w.minusWeeks(1) }
        return n
    }

    /**
     * Fitness estimate from the best recent effort. Easy runs underestimate fitness, so the
     * highest value of the last eight weeks is used.
     */
    fun estimateVdot(runs: List<RunRecord>, today: LocalDate): Double? =
        runs.filter { it.distanceM >= 3000 && it.durationS > 600 && !it.localDate.isBefore(today.minusWeeks(8)) }
            .maxOfOrNull { PaceCalc.vdot(it.distanceM.toDouble(), it.durationS.toDouble()) }

    fun longest(runs: List<RunRecord>): RunRecord? = runs.maxByOrNull { it.distanceM }

    /** Kilometre (or mile) splits computed from samples. */
    fun splits(samples: List<Sample>, unitM: Double): List<Split> {
        val out = ArrayList<Split>()
        var nextMark = unitM
        var lastT = 0
        var hrSum = 0; var hrN = 0
        var prev: Sample? = null
        for (s in samples) {
            s.hr?.let { hrSum += it; hrN++ }
            while (s.d >= nextMark) {
                // interpolate the moment the mark was crossed
                val p = prev
                val tCross = if (p != null && s.d > p.d) p.t + ((nextMark - p.d) / (s.d - p.d) * (s.t - p.t)).toInt() else s.t
                out += Split(out.size + 1, unitM.toInt(), tCross - lastT, if (hrN > 0) hrSum / hrN else null)
                lastT = tCross; hrSum = 0; hrN = 0
                nextMark += unitM
            }
            prev = s
        }
        val last = samples.lastOrNull()
        if (last != null) {
            val rest = (last.d - (nextMark - unitM)).toInt()
            if (rest >= 100) out += Split(out.size + 1, rest, last.t - lastT, if (hrN > 0) hrSum / hrN else null)
        }
        return out
    }
}
