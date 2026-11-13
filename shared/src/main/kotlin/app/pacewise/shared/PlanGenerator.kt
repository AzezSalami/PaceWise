package app.pacewise.shared

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a week-by-week marathon plan for any [TrainingMethod].
 *
 * All methods share the same skeleton (progressive long run, a step-back week every fourth
 * week, taper, race week) and differ in how many days they use, how long the long run gets
 * and what the two weekly "quality" slots contain.
 */
object PlanGenerator {

    fun generate(p: Profile, today: LocalDate = LocalDate.now()): TrainingPlan {
        val race = LocalDate.parse(p.raceDate)
        val paces = PaceCalc.pacesForMarathon(p.goalTimeS)
        val hr = PaceCalc.hrZones(p.age, p.maxHr)

        val raceMonday = race.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val available = ChronoUnit.WEEKS.between(thisMonday, raceMonday).toInt() + 1
        val n = min(p.planWeeks, max(available, 1))
        val start = raceMonday.minusWeeks((n - 1).toLong())

        val taperWeeks = when {
            n >= 10 -> 2
            n >= 5 -> 1
            else -> 0
        }
        val buildWeeks = n - 1 - taperWeeks

        val (startLong, levelPeak, easyLo, easyHi) = when (p.level) {
            Level.BEGINNER -> Quad(10.0, 30.0, 5.0, 8.0)
            Level.INTERMEDIATE -> Quad(14.0, 32.0, 7.0, 11.0)
            Level.ADVANCED -> Quad(18.0, 35.0, 10.0, 14.0)
        }
        val methodPeak = when (p.method) {
            TrainingMethod.CUMULATIVE_FATIGUE -> 26.0
            TrainingMethod.RUN_WALK -> levelPeak + 2
            TrainingMethod.THREE_QUALITY -> min(levelPeak, 32.0)
            else -> levelPeak
        }
        // Never ramp the long run by more than ~2.5 km a week when the plan is short.
        val peakLong = min(methodPeak, startLong + 2.5 * max(buildWeeks, 1))
        val lv = when (p.level) {
            Level.BEGINNER -> 0.75
            Level.INTERMEDIATE -> 1.0
            Level.ADVANCED -> 1.25
        }

        val weeks = ArrayList<PlanWeek>()
        var lastPhase: Phase? = null
        var phaseStep = 0
        for (i in 0 until n) {
            val weekStart = start.plusWeeks(i.toLong())
            if (i == n - 1) {
                weeks += raceWeek(p, paces, hr, i, weekStart, race, today)
                continue
            }
            val progress: Double
            val phase: Phase
            val cutback: Boolean
            val longKm: Double
            val volume: Double
            if (i < buildWeeks) {
                progress = if (buildWeeks > 1) i.toDouble() / (buildWeeks - 1) else 1.0
                phase = when {
                    progress < 0.34 -> Phase.BASE
                    progress < 0.7 -> Phase.BUILD
                    else -> Phase.PEAK
                }
                cutback = i % 4 == 3 && i < buildWeeks - 2
                val raw = startLong + (peakLong - startLong) * progress
                longKm = if (cutback) raw * 0.75 else raw
                volume = if (cutback) 0.85 else 1.0
            } else {
                progress = 1.0
                phase = Phase.TAPER
                cutback = false
                val first = i == buildWeeks
                val f = if (taperWeeks == 2) (if (first) 0.7 else 0.5) else 0.6
                longKm = peakLong * f
                volume = if (taperWeeks == 2 && first) 0.85 else 0.7
            }
            phaseStep = if (phase == lastPhase) phaseStep + 1 else 0
            lastPhase = phase

            val c = Ctx(
                p = p, paces = paces, hr = hr, week = i, phase = phase, progress = progress,
                phaseStep = phaseStep, cutback = cutback, longKm = half(longKm),
                easyKm = half((easyLo + (easyHi - easyLo) * progress) * volume),
                lv = lv,
            )
            weeks += buildWeek(c, weekStart)
        }
        return TrainingPlan(p.method, today.toString(), p.raceDate, paces, weeks)
    }

    // ---------------------------------------------------------------- week assembly

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)

    private class Ctx(
        val p: Profile,
        val paces: Paces,
        val hr: HrZones,
        val week: Int,
        val phase: Phase,
        val progress: Double,
        val phaseStep: Int,
        val cutback: Boolean,
        val longKm: Double,
        val easyKm: Double,
        val lv: Double,
    ) {
        val method get() = p.method

        val easyTarget: Target
            get() = when (method) {
                TrainingMethod.HEART_RATE -> Target(hrMin = hr.aerobicCeiling - 10, hrMax = hr.aerobicCeiling)
                TrainingMethod.POLARIZED_80_20 -> paces.easy.toTarget(hrMax = hr.easyCap)
                else -> paces.easy.toTarget()
            }

        val runWalk: RunWalk?
            get() = if (method != TrainingMethod.RUN_WALK) null else {
                val m = paces.marathon.mid
                when {
                    m < 330 -> RunWalk(240, 30)
                    m < 390 -> RunWalk(180, 60)
                    m < 450 -> RunWalk(120, 60)
                    else -> RunWalk(60, 60)
                }
            }

        fun reps(n: Int) = max(2, (n * lv).roundToInt())
        fun <T> pick(list: List<T>, idx: Int = phaseStep) = list[idx.coerceIn(0, list.size - 1)]
    }

    /** Days after the long run, in the order running days are added as the week fills up. */
    private val slotOrder = listOf(3, 5, 2, 6, 4, 1)

    private fun buildWeek(c: Ctx, weekStart: LocalDate): PlanWeek {
        val p = c.p
        fun dateFor(offset: Int): LocalDate {
            val dow = (p.longRunDay - 1 + offset) % 7
            return weekStart.plusDays(dow.toLong())
        }

        val out = ArrayList<Workout>()
        out += longRun(c, dateFor(0))
        for (offset in slotOrder.take(p.effectiveDays - 1)) {
            val date = dateFor(offset)
            val w = when (offset) {
                3 -> quality1(c, date)
                5 -> quality2(c, date)
                else -> null
            }
            out += w ?: when (offset) {
                1 -> easy(c, date, c.easyKm * 0.7, recovery = true)
                6 -> easy(c, date, c.easyKm * 0.8)
                else -> easy(c, date, c.easyKm)
            }
        }
        if (c.method == TrainingMethod.THREE_QUALITY) {
            for (offset in listOf(1, 4)) {
                val date = dateFor(offset)
                out += Workout(
                    id = id(c.week, date), date = date.toString(), type = WorkoutType.CROSS_TRAIN,
                    title = "Cross-training",
                    description = "30–45 minutes of cycling, swimming or rowing at a steady, " +
                        "conversational effort. No running today.",
                    plannedDurationS = 40 * 60,
                )
            }
        }
        val focus = when {
            c.cutback -> "Step-back week: absorb the training"
            c.phase == Phase.BASE -> "Build the routine and your aerobic base"
            c.phase == Phase.BUILD -> "More volume, more quality"
            c.phase == Phase.PEAK -> "Biggest weeks and longest runs"
            else -> "Ease off and freshen up"
        }
        return PlanWeek(c.week, weekStart.toString(), c.phase, focus, out.sortedBy { it.date })
    }

    private fun raceWeek(
        p: Profile, paces: Paces, hr: HrZones, index: Int, weekStart: LocalDate, race: LocalDate, today: LocalDate,
    ): PlanWeek {
        val c = Ctx(p, paces, hr, index, Phase.RACE, 1.0, 0, false, 0.0, 6.0, 1.0)
        val out = ArrayList<Workout>()
        fun ok(d: LocalDate) = !d.isBefore(weekStart) && !d.isBefore(today)

        race.minusDays(5).takeIf(::ok)?.let { out += easy(c, it, 6.0, strides = 4) }
        race.minusDays(3).takeIf(::ok)?.let { d ->
            out += if (p.method == TrainingMethod.HEART_RATE || p.method == TrainingMethod.RUN_WALK) {
                easy(c, d, 5.0)
            } else {
                val b = WB(c).apply {
                    warm(2.0)
                    repeat(3) { i ->
                        run("Marathon pace ${i + 1}/3", 1.0, paces.marathon.toTarget())
                        if (i < 2) jog(90)
                    }
                    cool(1.0)
                }
                workout(c, d, WorkoutType.MARATHON_PACE, "Race-pace tune-up",
                    "A short reminder of goal pace. It should feel easy. Stop wanting more.", b)
            }
        }
        if (p.effectiveDays >= 4) race.minusDays(1).takeIf(::ok)?.let {
            out += easy(c, it, 3.0, title = "Shake-out", desc = "Very short and relaxed. Loosen the legs, then rest up.")
        }
        val raceStep = Step(
            StepKind.RUN, "Marathon", distanceM = MARATHON_M, target = paces.marathon.toTarget(), runWalk = c.runWalk,
        )
        out += Workout(
            id = id(index, race), date = race.toString(), type = WorkoutType.RACE, title = p.raceName,
            description = "Race day. Start at the slow end of your target pace, hold steady through " +
                "30 km and use whatever is left after that. The watch keeps you on pace.",
            steps = listOf(raceStep), plannedDistanceM = MARATHON_M, plannedDurationS = p.goalTimeS,
        )
        return PlanWeek(index, weekStart.toString(), Phase.RACE, "Rest, stay loose, race", out.sortedBy { it.date })
    }

    // ---------------------------------------------------------------- workout builders

    private class WB(val c: Ctx) {
        val steps = ArrayList<Step>()
        fun warm(km: Double = 2.0) { steps += Step(StepKind.WARMUP, "Warm-up", distanceM = m(km), target = c.easyTarget, runWalk = c.runWalk) }
        fun cool(km: Double = 1.5) { steps += Step(StepKind.COOLDOWN, "Cool-down", distanceM = m(km), target = c.easyTarget, runWalk = c.runWalk) }
        fun run(label: String, km: Double, target: Target?, runWalk: RunWalk? = null) {
            steps += Step(StepKind.RUN, label, distanceM = m(km), target = target, runWalk = runWalk)
        }
        fun runM(label: String, meters: Int, target: Target?) { steps += Step(StepKind.RUN, label, distanceM = meters, target = target) }
        fun timed(label: String, sec: Int, target: Target?) { steps += Step(StepKind.RUN, label, durationS = sec, target = target) }
        fun jog(sec: Int) { steps += Step(StepKind.RECOVER, "Recovery jog", durationS = sec) }
        fun jogM(meters: Int) { steps += Step(StepKind.RECOVER, "Recovery jog", distanceM = meters) }

        /** n × (work + recovery), with no recovery after the last rep. */
        fun repsM(n: Int, meters: Int, name: String, target: Target?, recS: Int = 0, recM: Int = 0) {
            for (i in 1..n) {
                runM("$name $i/$n", meters, target)
                if (i < n) { if (recM > 0) jogM(recM) else jog(recS) }
            }
        }
        fun repsT(n: Int, sec: Int, name: String, target: Target?, recS: Int) {
            for (i in 1..n) {
                timed("$name $i/$n", sec, target)
                if (i < n) jog(recS)
            }
        }
    }

    private fun m(km: Double) = (half(km) * 1000).roundToInt()
    private fun half(km: Double) = max(1.0, (km * 2).roundToInt() / 2.0)
    private fun id(week: Int, date: LocalDate) = "w${week + 1}-$date"

    private fun workout(c: Ctx, date: LocalDate, type: WorkoutType, title: String, desc: String, b: WB): Workout {
        var dist = 0.0
        var dur = 0.0
        for (s in b.steps) {
            val pace = when {
                s.kind == StepKind.RECOVER -> c.paces.recovery.mid
                s.target?.hasPace == true -> (s.target.paceFast!! + s.target.paceSlow!!) / 2
                else -> c.paces.easy.mid
            }.toDouble()
            if (s.distanceM != null) { dist += s.distanceM; dur += s.distanceM / 1000.0 * pace }
            else if (s.durationS != null) { dur += s.durationS; dist += s.durationS / pace * 1000.0 }
        }
        return Workout(
            id = id(c.week, date), date = date.toString(), type = type, title = title, description = desc,
            steps = b.steps, plannedDistanceM = (dist / 100).roundToInt() * 100, plannedDurationS = dur.roundToInt(),
        )
    }

    private fun easy(
        c: Ctx, date: LocalDate, km: Double, recovery: Boolean = false, strides: Int = 0,
        title: String? = null, desc: String? = null,
    ): Workout {
        val b = WB(c)
        val target = if (recovery && c.method != TrainingMethod.HEART_RATE) c.paces.recovery.toTarget(c.easyTarget.hrMax) else c.easyTarget
        b.run(if (recovery) "Recovery" else "Easy", km, target, c.runWalk)
        if (strides > 0) b.repsT(strides, 20, "Stride", c.paces.repetition.toTarget(), 60)
        val d = desc ?: when {
            c.method == TrainingMethod.HEART_RATE ->
                "Stay under ${c.hr.aerobicCeiling} bpm for the whole run, even if that means walking the hills."
            c.method == TrainingMethod.RUN_WALK ->
                "Run ${Fmt.duration(c.runWalk!!.runS)}, walk ${Fmt.duration(c.runWalk!!.walkS)}, repeat. Take the walk breaks from the very start."
            recovery -> "Very gentle. The only goal is to move and recover from yesterday."
            strides > 0 -> "Relaxed running, then $strides short, fast-but-smooth strides with a full recovery."
            else -> "Comfortable, conversational pace. If you can't talk in full sentences, slow down."
        }
        return workout(c, date, if (recovery) WorkoutType.RECOVERY else WorkoutType.EASY,
            title ?: if (recovery) "Recovery run" else "Easy run", d, b)
    }

    private fun longRun(c: Ctx, date: LocalDate): Workout {
        val km = c.longKm
        val b = WB(c)
        val paces = c.paces
        var title = "Long run"
        var desc = "Slow and steady. Time on your feet matters more than pace today."
        val building = c.phase == Phase.BUILD || c.phase == Phase.PEAK
        when (c.method) {
            TrainingMethod.HEART_RATE -> {
                b.run("Long", km, c.easyTarget)
                desc = "Keep your heart rate under ${c.hr.aerobicCeiling} bpm all the way. Expect to slow down late in the run; that's fine."
            }
            TrainingMethod.RUN_WALK -> {
                b.run("Long", km, paces.long.shift(20).toTarget(), c.runWalk)
                desc = "Take every walk break from the first one. You should finish feeling you could go further."
            }
            TrainingMethod.THREE_QUALITY -> {
                b.run("Long", km, paces.marathon.shift(20).let { PaceRange(it.fast - 5, it.slow + 5) }.toTarget())
                desc = "Quicker than a typical long run: 10–30 seconds per km slower than marathon pace."
            }
            TrainingMethod.CUMULATIVE_FATIGUE -> {
                b.run("Long", km, PaceRange(paces.long.fast - 10, paces.long.slow - 15).toTarget())
                desc = "Moderate, not slow. With the week's training in your legs, this rehearses the last part of the marathon."
            }
            TrainingMethod.HIGH_MILEAGE_LT, TrainingMethod.VDOT -> {
                if (building && c.phaseStep % 2 == 1 && !c.cutback && km >= 16) {
                    val mp = half(km * 0.4)
                    b.run("Easy", km - mp, paces.long.toTarget())
                    b.run("Marathon pace", mp, paces.marathon.toTarget())
                    title = "Long run, fast finish"
                    desc = "Run easy, then finish the last ${fmtKm(mp)} at marathon pace. A key race-specific session."
                } else b.run("Long", km, paces.long.toTarget())
            }
            TrainingMethod.POLARIZED_80_20 -> b.run("Long", km, paces.long.toTarget(c.hr.easyCap))
            else -> {
                if (c.method == TrainingMethod.CLASSIC && c.phase == Phase.PEAK && c.phaseStep % 2 == 1 && c.p.level != Level.BEGINNER) {
                    val mp = half(km * 0.25)
                    b.run("Easy", km - mp, paces.long.toTarget())
                    b.run("Marathon pace", mp, paces.marathon.toTarget())
                    title = "Long run, fast finish"
                    desc = "Mostly easy, with the last ${fmtKm(mp)} at marathon pace."
                } else b.run("Long", km, paces.long.toTarget())
            }
        }
        return workout(c, date, WorkoutType.LONG, title, desc, b)
    }

    private fun fmtKm(km: Double) = if (km % 1.0 == 0.0) "${km.toInt()} km" else "$km km"

    // ---------------------------------------------------------------- quality sessions

    private fun quality1(c: Ctx, date: LocalDate): Workout? {
        val p = c.paces
        val taper = c.phase == Phase.TAPER
        val b = WB(c)
        fun mpRun(km: Double) = b.run { warm(2.0); run("Marathon pace", km, p.marathon.toTarget()); cool(1.5) }
        when (c.method) {
            TrainingMethod.CLASSIC -> {
                if (c.phase == Phase.BASE) return null
                if (taper) { mpRun(5.0); return workout(c, date, WorkoutType.MARATHON_PACE, "Marathon-pace run", "A short block at goal pace to stay sharp.", b) }
                if (c.phase == Phase.PEAK && c.phaseStep % 2 == 0) {
                    val min = c.pick(listOf(20, 25, 30, 35), c.phaseStep / 2 + 1)
                    b.warm(2.0); b.timed("Tempo", min * 60, p.threshold.toTarget()); b.cool(1.5)
                    return workout(c, date, WorkoutType.TEMPO, "Tempo run", "$min minutes comfortably hard: you can say a few words, not sentences.", b)
                }
                val km = min(5.0 + c.phaseStep, 13.0 * c.lv)
                mpRun(km)
                return workout(c, date, WorkoutType.MARATHON_PACE, "Marathon-pace run", "Lock into goal pace and learn what it feels like.", b)
            }
            TrainingMethod.CUMULATIVE_FATIGUE -> {
                b.warm(2.5)
                if (taper || c.progress >= 0.5) {
                    val strength = PaceRange(p.marathon.fast - 6, p.marathon.slow - 8).toTarget()
                    val (n, d) = if (taper) 3 to 1600 else c.pick(listOf(6 to 1600, 4 to 2400, 3 to 3200, 2 to 4800, 3 to 3200, 4 to 2400, 6 to 1600), c.week % 7)
                    b.repsM(n, d, "Strength", strength, recM = 400)
                    b.cool(2.0)
                    return workout(c, date, WorkoutType.INTERVALS, "Strength: $n × ${d} m",
                        "Slightly quicker than marathon pace with short jogs. Controlled, never a sprint.", b)
                }
                val (n, d) = c.pick(listOf(12 to 400, 8 to 600, 6 to 800, 5 to 1000, 4 to 1200, 3 to 1600), c.week % 6)
                b.repsM(n, d, "Speed", p.interval.toTarget(), recM = 400)
                b.cool(2.0)
                return workout(c, date, WorkoutType.INTERVALS, "Speed: $n × ${d} m", "Hard but even. Every rep at the same pace, jogging the recoveries.", b)
            }
            TrainingMethod.HIGH_MILEAGE_LT -> {
                b.warm(3.0)
                if (c.phase == Phase.PEAK) {
                    val (n, d) = c.pick(listOf(5 to 1000, 6 to 1000, 5 to 1200, 6 to 1200))
                    b.repsM(c.reps(n), d, "VO2max", p.interval.toTarget(), recS = 120)
                    b.cool(2.0)
                    return workout(c, date, WorkoutType.INTERVALS, "VO2max: ${c.reps(n)} × $d m", "About 5 km race effort with 2-minute jogs.", b)
                }
                val km = if (taper) 5.0 else min(6.0 + c.week * 0.6, 11.0) * c.lv.coerceAtMost(1.1)
                b.run("Threshold", km, p.threshold.toTarget()); b.cool(2.0)
                return workout(c, date, WorkoutType.TEMPO, "Lactate-threshold run", "${fmtKm(half(km))} at the pace you could race for about an hour.", b)
            }
            TrainingMethod.VDOT -> {
                b.warm(3.0)
                val q = if (taper) 3 else (c.progress * 4).toInt().coerceAtMost(3)
                when (q) {
                    0 -> {
                        val bb = WB(c)
                        bb.run("Easy", c.easyKm, c.easyTarget)
                        bb.repsT(c.reps(8), 20, "Stride", p.repetition.toTarget(), 60)
                        return workout(c, date, WorkoutType.EASY, "Easy run + strides", "Easy running, then short fast strides to build leg speed and form.", bb)
                    }
                    1 -> {
                        val (n, d) = c.pick(listOf(8 to 200, 6 to 400, 8 to 400, 10 to 400))
                        b.repsM(c.reps(n), d, "R pace", p.repetition.toTarget(), recM = d)
                        b.cool(2.0)
                        return workout(c, date, WorkoutType.INTERVALS, "Repetitions: ${c.reps(n)} × $d m", "Fast and relaxed with full recoveries. This is about speed and economy.", b)
                    }
                    2 -> {
                        val (n, d) = c.pick(listOf(5 to 1000, 4 to 1200, 6 to 1000, 5 to 1200))
                        b.repsM(c.reps(n), d, "I pace", p.interval.toTarget(), recS = 180)
                        b.cool(2.0)
                        return workout(c, date, WorkoutType.INTERVALS, "Intervals: ${c.reps(n)} × $d m", "Hard, at roughly 5 km race pace, with 3-minute jog recoveries.", b)
                    }
                    else -> {
                        val (n, d) = if (taper) 3 to 1600 else c.pick(listOf(4 to 1600, 5 to 1600, 3 to 3200, 4 to 3200))
                        b.repsM(if (taper) n else max(2, (n * c.lv.coerceAtMost(1.1)).roundToInt()), d, "T pace", p.threshold.toTarget(), recS = if (d > 2000) 120 else 60)
                        b.cool(2.0)
                        return workout(c, date, WorkoutType.TEMPO, "Cruise intervals: $n × $d m", "Threshold pace with short rests: comfortably hard, fully under control.", b)
                    }
                }
            }
            TrainingMethod.RUN_WALK -> {
                if (taper || c.week % 4 != 2) return null
                b.warm(1.5); b.runM("Magic mile", 1600, null); b.cool(1.5)
                return workout(c, date, WorkoutType.TEST, "Magic mile",
                    "Run one mile (1.6 km) as fast as you can evenly sustain, without walk breaks. Your time shows how your fitness is moving.", b)
            }
            TrainingMethod.POLARIZED_80_20 -> {
                b.warm(2.5)
                if (taper) {
                    b.repsT(4, 180, "Hard", p.interval.toTarget(), 120); b.cool(2.0)
                    return workout(c, date, WorkoutType.INTERVALS, "Intervals: 4 × 3 min", "A short hard session to keep the engine tuned.", b)
                }
                if (c.week % 2 == 0) {
                    val (n, min) = c.pick(listOf(5 to 3, 6 to 3, 5 to 4, 6 to 4, 5 to 5, 6 to 5), c.week / 2)
                    b.repsT(c.reps(n), min * 60, "Hard", p.interval.toTarget(), if (min >= 4) 180 else 120)
                    b.cool(2.0)
                    return workout(c, date, WorkoutType.INTERVALS, "Intervals: ${c.reps(n)} × $min min", "This is your 20%. Make it properly hard.", b)
                }
                val (n, min) = c.pick(listOf(2 to 10, 3 to 8, 3 to 10, 2 to 15, 3 to 12), c.week / 2)
                b.repsT(n, min * 60, "Tempo", p.threshold.toTarget(), 120); b.cool(2.0)
                return workout(c, date, WorkoutType.TEMPO, "Tempo: $n × $min min", "Threshold effort with short jogs between blocks.", b)
            }
            TrainingMethod.THREE_QUALITY -> {
                b.warm(2.0)
                val t = p.interval.toTarget()
                val title: String
                if (taper) { b.repsM(5, 400, "Rep", t, recS = 90); title = "Track: 5 × 400 m" }
                else when (c.week % 6) {
                    0 -> { b.repsM(c.reps(6), 800, "Rep", t, recS = 90); title = "Track: ${c.reps(6)} × 800 m" }
                    1 -> { b.repsM(c.reps(5), 1000, "Rep", t, recM = 400); title = "Track: ${c.reps(5)} × 1000 m" }
                    2 -> { b.repsM(3, 1600, "Rep", p.interval.shift(8).toTarget(), recS = 90); title = "Track: 3 × 1600 m" }
                    3 -> { b.repsM(c.reps(12), 400, "Rep", p.repetition.shift(6).toTarget(), recS = 90); title = "Track: ${c.reps(12)} × 400 m" }
                    4 -> {
                        val ladder = listOf(400, 800, 1200, 1600, 1200, 800, 400)
                        ladder.forEachIndexed { i, d -> b.runM("$d m", d, t); if (i < ladder.size - 1) b.jogM(400) }
                        title = "Track: ladder 400–1600 m"
                    }
                    else -> { b.repsM(c.reps(4), 1200, "Rep", t, recS = 120); title = "Track: ${c.reps(4)} × 1200 m" }
                }
                b.cool(2.0)
                return workout(c, date, WorkoutType.INTERVALS, title, "Key run 1. Hit the paces; this session drives your speed.", b)
            }
            TrainingMethod.AEROBIC_BASE -> {
                if (taper) {
                    b.run("Easy", 6.0, c.easyTarget); b.repsT(6, 25, "Stride", p.repetition.toTarget(), 75)
                    return workout(c, date, WorkoutType.EASY, "Easy run + strides", "Keep the legs quick without adding fatigue.", b)
                }
                return when {
                    c.progress < 0.45 -> {
                        val km = min(8.0 + c.week, 14.0) * c.lv.coerceIn(0.8, 1.1)
                        b.warm(1.5); b.run("Steady", km, PaceRange(p.marathon.fast + 12, p.marathon.slow + 28).toTarget()); b.cool(1.0)
                        workout(c, date, WorkoutType.TEMPO, "Steady aerobic run", "Strong but aerobic, the classic 'three-quarter effort'. Finish pleasantly tired, not spent.", b)
                    }
                    c.progress < 0.62 -> {
                        val n = c.reps(6 + c.phaseStep * 2)
                        b.warm(3.0); b.repsT(n, 75, "Uphill", null, 120); b.cool(2.0)
                        workout(c, date, WorkoutType.HILLS, "Hill circuit: $n × 75 s", "Find a moderate hill. Bound up strongly with high knees and jog back down to recover.", b)
                    }
                    c.progress < 0.85 -> {
                        val (n, d) = c.pick(listOf(6 to 800, 5 to 1000, 4 to 1200, 3 to 1600, 5 to 1000))
                        b.warm(3.0); b.repsM(c.reps(n), d, "Rep", p.interval.toTarget(), recM = d / 2); b.cool(2.0)
                        workout(c, date, WorkoutType.INTERVALS, "Anaerobic: ${c.reps(n)} × $d m", "Hard repetitions with equal-time recovery jogs.", b)
                    }
                    else -> {
                        b.warm(3.0); b.run("Time trial", if (c.phaseStep % 2 == 0) 5.0 else 3.0, p.threshold.shift(-4).toTarget()); b.cool(2.0)
                        workout(c, date, WorkoutType.TEST, "Time trial", "An even, controlled hard effort, about 90%. A rehearsal, not a race.", b)
                    }
                }
            }
            TrainingMethod.HEART_RATE -> {
                if (taper || c.week % 4 != 3) return null
                val t = Target(hrMin = c.hr.aerobicCeiling - 5, hrMax = c.hr.aerobicCeiling)
                b.warm(2.0); b.repsM(5, 1600, "Test mile", t, recS = 0); b.cool(1.5)
                return workout(c, date, WorkoutType.TEST, "Aerobic test",
                    "Five test miles right at your aerobic heart rate on a flat route. Compare the pace with last month: faster at the same heart rate means it's working.", b)
            }
            TrainingMethod.THRESHOLD_FOCUS -> {
                val sub = p.threshold.shift(8).toTarget(c.hr.thresholdHigh)
                b.warm(2.5)
                val (n, min) = if (taper) 3 to 6 else c.pick(listOf(4 to 6, 5 to 6, 4 to 8, 5 to 8, 4 to 10, 5 to 10, 6 to 10), c.week / 2)
                val reps = if (taper) n else c.reps(n)
                b.repsT(reps, min * 60, "Sub-threshold", sub, 75); b.cool(2.0)
                return workout(c, date, WorkoutType.TEMPO, "Threshold: $reps × $min min",
                    "Just below threshold with short rests. You should finish feeling you could do two more.", b)
            }
        }
    }

    private fun quality2(c: Ctx, date: LocalDate): Workout? {
        val p = c.paces
        val taper = c.phase == Phase.TAPER
        val b = WB(c)
        when (c.method) {
            TrainingMethod.CUMULATIVE_FATIGUE -> {
                val km = if (taper) 8.0 else half(8.0 + 8.0 * c.progress) * c.lv.coerceIn(0.85, 1.0)
                b.warm(2.0); b.run("Marathon pace", km, p.marathon.toTarget()); b.cool(2.0)
                return workout(c, date, WorkoutType.MARATHON_PACE, "Marathon-pace tempo",
                    "${fmtKm(half(km))} at exactly goal pace. Not faster: the discipline is the workout.", b)
            }
            TrainingMethod.HIGH_MILEAGE_LT -> {
                val km = (c.longKm * 0.65).coerceIn(11.0, 24.0)
                b.run("Medium-long", km, p.long.toTarget())
                return workout(c, date, WorkoutType.MEDIUM_LONG, "Medium-long run", "A second longer run midweek, at an honest endurance pace.", b)
            }
            TrainingMethod.VDOT -> {
                if (taper) return null
                val q = (c.progress * 4).toInt().coerceAtMost(3)
                b.warm(3.0)
                return when (q) {
                    0 -> null
                    1 -> {
                        b.timed("T pace", 20 * 60, p.threshold.toTarget()); b.cool(2.0)
                        workout(c, date, WorkoutType.TEMPO, "Tempo: 20 min", "Twenty steady minutes at threshold pace.", b)
                    }
                    2 -> {
                        b.repsM(3, 1600, "T pace", p.threshold.toTarget(), recS = 60); b.jog(120)
                        b.run("Marathon pace", 4.0 * c.lv, p.marathon.toTarget()); b.cool(1.5)
                        workout(c, date, WorkoutType.TEMPO, "Threshold + marathon pace", "Threshold reps, then settle straight into marathon pace on tired legs.", b)
                    }
                    else -> {
                        val km = half((10.0 + c.phaseStep * 2).coerceAtMost(16.0) * c.lv.coerceAtMost(1.1))
                        b.run("Marathon pace", km, p.marathon.toTarget()); b.cool(2.0)
                        workout(c, date, WorkoutType.MARATHON_PACE, "Marathon-pace run", "${fmtKm(km)} at goal pace. Practise fuelling as you will on race day.", b)
                    }
                }
            }
            TrainingMethod.POLARIZED_80_20 -> {
                if (taper || c.p.effectiveDays < 6 || c.phase == Phase.BASE) return null
                val n = c.reps(8)
                b.warm(2.0); b.repsT(n, 60, "Fast", p.interval.toTarget(), 60); b.cool(2.0)
                return workout(c, date, WorkoutType.INTERVALS, "Fartlek: $n × 1 min", "One minute fast, one minute easy. Playful, not forced.", b)
            }
            TrainingMethod.THREE_QUALITY -> {
                b.warm(2.0)
                val title: String
                if (taper) { b.run("Tempo", 5.0, p.threshold.shift(6).toTarget()); title = "Short tempo" }
                else when (c.week % 3) {
                    0 -> { b.run("Tempo", 5.0 * c.lv.coerceAtLeast(0.8), p.threshold.toTarget()); title = "Short tempo" }
                    1 -> { b.run("Tempo", 8.0 * c.lv.coerceAtLeast(0.8), p.threshold.shift(10).toTarget()); title = "Mid tempo" }
                    else -> { b.run("Tempo", 13.0 * c.lv.coerceIn(0.8, 1.1), p.threshold.shift(20).toTarget()); title = "Long tempo" }
                }
                b.cool(1.5)
                return workout(c, date, WorkoutType.TEMPO, title, "Key run 2. Sustained, comfortably hard running.", b)
            }
            TrainingMethod.AEROBIC_BASE -> {
                if (taper) return null
                return when {
                    c.progress < 0.62 -> {
                        b.run("Medium-long", (c.longKm * 0.6).coerceIn(10.0, 22.0), p.long.toTarget())
                        workout(c, date, WorkoutType.MEDIUM_LONG, "Medium-long run", "A second longer aerobic run to deepen the base.", b)
                    }
                    c.progress < 0.85 -> {
                        val min = c.pick(listOf(20, 25, 30))
                        b.warm(2.0); b.timed("Tempo", min * 60, p.threshold.toTarget()); b.cool(1.5)
                        workout(c, date, WorkoutType.TEMPO, "Tempo: $min min", "Steady and strong at threshold effort.", b)
                    }
                    else -> {
                        val n = c.reps(10)
                        b.warm(3.0); b.repsT(n, 30, "Sprint", p.repetition.toTarget(), 30); b.cool(2.0)
                        workout(c, date, WorkoutType.INTERVALS, "Wind sprints: $n × 30 s", "Quick 30-second efforts with 30-second floats to sharpen up.", b)
                    }
                }
            }
            TrainingMethod.HEART_RATE -> {
                if (c.phase != Phase.PEAK) return null
                val km = half((8.0 + c.phaseStep * 2).coerceAtMost(14.0))
                b.warm(2.0); b.run("Marathon pace", km, p.marathon.toTarget()); b.cool(1.5)
                return workout(c, date, WorkoutType.MARATHON_PACE, "Race-pace rehearsal",
                    "The one session above your aerobic heart rate: ${fmtKm(km)} at goal pace so race day isn't a surprise.", b)
            }
            TrainingMethod.THRESHOLD_FOCUS -> {
                if (taper || c.p.effectiveDays < 5) return null
                b.warm(2.5)
                val t = p.threshold.toTarget(c.hr.thresholdHigh)
                val idx = c.week / 2
                val title: String
                if (idx < 2) {
                    val n = c.reps(c.pick(listOf(8, 10), idx))
                    b.repsT(n, 180, "Threshold", t, 60); title = "Threshold: $n × 3 min"
                } else {
                    val n = c.reps(c.pick(listOf(8, 10, 12), idx - 2))
                    b.repsM(n, 1000, "Threshold", t, recS = 60); title = "Threshold: $n × 1000 m"
                }
                b.cool(2.0)
                return workout(c, date, WorkoutType.TEMPO, title, "Shorter threshold reps with 1-minute rests. Smooth and controlled.", b)
            }
            else -> return null
        }
    }
}
