package app.pacewise.shared

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun vdotMatchesPublishedTables() {
        // 3:30 marathon is roughly VDOT 44.6; 3:00 is roughly 53.5
        assertEquals(44.6, PaceCalc.vdot(42195.0, 3.5 * 3600), 0.5)
        assertEquals(53.5, PaceCalc.vdot(42195.0, 3.0 * 3600), 0.5)
        assertEquals(3.5 * 3600, PaceCalc.predictTime(PaceCalc.vdot(42195.0, 3.5 * 3600)).toDouble(), 5.0)
        // a 50:00 10k predicts a marathon a little under 3:51
        val t = PaceCalc.predictTime(PaceCalc.vdot(10000.0, 3000.0))
        assertTrue(t in 13500..14100, "predicted $t")
    }

    @Test fun pacesAreOrdered() {
        for (goal in listOf(2.75, 3.5, 4.5, 5.5, 6.5)) {
            val p = PaceCalc.pacesForMarathon((goal * 3600).toInt())
            assertTrue(p.repetition.slow < p.interval.slow, "$goal R<I")
            assertTrue(p.interval.slow < p.threshold.slow, "$goal I<T")
            assertTrue(p.threshold.slow < p.marathon.slow, "$goal T<M")
            assertTrue(p.marathon.slow < p.easy.fast, "$goal M<E")
            assertTrue(p.easy.fast < p.easy.slow)
        }
    }

    @Test fun everyMethodBuildsAValidPlan() {
        for (method in TrainingMethod.entries) for (level in Level.entries) for (days in 3..7) for (longDay in listOf(6, 7)) {
            val profile = Profile(
                method = method, raceDate = "2027-04-25", goalTimeS = 4 * 3600, level = level,
                daysPerWeek = days, longRunDay = longDay, planWeeks = method.defaultWeeks,
            )
            val plan = PlanGenerator.generate(profile, today)
            val tag = "$method/$level/$days"
            assertEquals(method.defaultWeeks, plan.weeks.size, tag)
            val all = plan.allWorkouts()
            assertEquals(all.size, all.map { it.date }.toSet().size, "$tag one workout per day")
            assertEquals(all.size, all.map { it.id }.toSet().size, "$tag unique ids")
            val race = all.single { it.type == WorkoutType.RACE }
            assertEquals("2027-04-25", race.date)
            assertEquals(all.maxOf { it.date }, race.date, "$tag race is last")
            for ((i, week) in plan.weeks.dropLast(1).withIndex()) {
                val runs = week.workouts.filter { it.type.isRun }
                assertEquals(profile.effectiveDays, runs.size, "$tag week $i run days")
                val long = runs.single { it.type == WorkoutType.LONG }
                assertEquals(longDay, long.localDate.dayOfWeek.value, tag)
                assertTrue(runs.all { it.plannedDistanceM >= long.plannedDistanceM / 5 && it.plannedDistanceM <= long.plannedDistanceM || it === long || it.plannedDistanceM < 30000 }, tag)
                for (w in runs) {
                    assertTrue(w.steps.isNotEmpty(), "$tag ${w.title} has steps")
                    assertTrue(w.plannedDistanceM in 2000..40000, "$tag ${w.title} ${w.plannedDistanceM}")
                    for (s in w.steps) assertTrue((s.distanceM == null) != (s.durationS == null), "$tag step length")
                }
            }
            val longs = plan.weeks.dropLast(1).map { w -> w.workouts.single { it.type == WorkoutType.LONG }.plannedDistanceM }
            val peak = longs.max()
            val cap = if (method == TrainingMethod.CUMULATIVE_FATIGUE) 26000 else 37000
            assertTrue(peak in 24000..cap, "$tag peak long $peak")
            assertTrue(longs.last() < peak * 0.6, "$tag tapers")
            // weekly volume never jumps wildly
            val vols = plan.weeks.dropLast(1).map { it.plannedDistanceM }
            assertTrue(vols.max() < 190_000, "$tag max week ${vols.max()}")
        }
    }

    @Test fun shortNoticePlanIsCompressed() {
        val p = Profile(raceDate = "2026-12-20", planWeeks = 18)
        val plan = PlanGenerator.generate(p, today)
        assertEquals(6, plan.weeks.size)
        assertTrue(plan.allWorkouts().any { it.type == WorkoutType.RACE })
        val peak = plan.allWorkouts().filter { it.type == WorkoutType.LONG }.maxOf { it.plannedDistanceM }
        assertTrue(peak <= 21000, "ramp is limited: $peak")
    }

    @Test fun methodSpecificTargets() {
        val hr = PlanGenerator.generate(Profile(method = TrainingMethod.HEART_RATE, raceDate = "2027-04-25", age = 40, daysPerWeek = 5), today)
        val easy = hr.weeks[0].workouts.first { it.type == WorkoutType.EASY }
        assertEquals(140, easy.steps[0].target!!.hrMax)
        assertNull(easy.steps[0].target!!.paceFast)

        val rw = PlanGenerator.generate(Profile(method = TrainingMethod.RUN_WALK, raceDate = "2027-04-25", goalTimeS = 5 * 3600), today)
        assertTrue(rw.allWorkouts().filter { it.type == WorkoutType.LONG }.all { it.steps[0].runWalk != null })
        assertNotNull(rw.allWorkouts().single { it.type == WorkoutType.RACE }.steps[0].runWalk)

        val first = PlanGenerator.generate(Profile(method = TrainingMethod.THREE_QUALITY, raceDate = "2027-04-25", daysPerWeek = 6), today)
        assertEquals(2, first.weeks[0].workouts.count { it.type == WorkoutType.CROSS_TRAIN })
        assertEquals(3, first.weeks[0].workouts.count { it.type.isRun })
    }

    @Test fun engineWalksThroughSteps() {
        val w = Workout(
            "x", "2026-11-13", WorkoutType.INTERVALS, "t", "", steps = listOf(
                Step(StepKind.WARMUP, "Warm-up", distanceM = 1000),
                Step(StepKind.RUN, "Rep 1/2", distanceM = 400, target = Target(240, 250)),
                Step(StepKind.RECOVER, "Jog", durationS = 60),
                Step(StepKind.RUN, "Rep 2/2", distanceM = 400, target = Target(240, 250)),
            ), plannedDistanceM = 2000,
        )
        val e = WorkoutEngine(w)
        var s = e.update(500.0, 150.0)
        assertEquals(0, s.stepIndex); assertEquals(500, s.stepRemainingM); assertEquals(0.5f, s.stepProgress)
        s = e.update(1100.0, 330.0)
        assertEquals(1, s.stepIndex); assertEquals(300, s.stepRemainingM)
        assertEquals(240, s.activeTarget!!.paceFast)
        s = e.update(1400.0, 400.0)
        assertEquals(2, s.stepIndex); assertEquals(60, s.stepRemainingS); assertNull(s.activeTarget)
        s = e.update(1500.0, 430.0)
        assertEquals(30, s.stepRemainingS)
        s = e.skip()
        assertEquals(3, s.stepIndex)
        s = e.update(1950.0, 530.0)
        assertTrue(s.finished)
        assertEquals(4, e.completed.size)
        assertEquals(400.0, e.completed[1].distanceM)
        assertEquals(30.0, e.completed[2].durationS)
        // free run has no steps and never finishes
        assertTrue(!WorkoutEngine(null).update(5000.0, 1500.0).finished)
    }

    @Test fun runWalkSegments() {
        val w = Workout("x", "2026-11-13", WorkoutType.EASY, "t", "", steps = listOf(
            Step(StepKind.RUN, "Easy", distanceM = 5000, target = Target(400, 440), runWalk = RunWalk(120, 60)),
        ))
        val e = WorkoutEngine(w)
        var s = e.update(100.0, 30.0)
        assertEquals(Segment.RUN, s.segment); assertEquals(90, s.segmentRemainingS); assertNotNull(s.activeTarget)
        s = e.update(450.0, 130.0)
        assertEquals(Segment.WALK, s.segment); assertEquals(50, s.segmentRemainingS); assertNull(s.activeTarget)
        s = e.update(600.0, 185.0)
        assertEquals(Segment.RUN, s.segment)
    }

    @Test fun coachCues() {
        val t = Target(paceFast = 300, paceSlow = 310)
        assertEquals(Guidance(Cue.SPEED_UP, 20), PaceCoach.evaluate(t, 330.0, null))
        assertEquals(Guidance(Cue.SLOW_DOWN, 15), PaceCoach.evaluate(t, 285.0, null))
        assertEquals(Cue.ON_TARGET, PaceCoach.evaluate(t, 305.0, 150).cue)
        assertEquals(Cue.NONE, PaceCoach.evaluate(t, null, 150).cue)
        assertEquals(Cue.NONE, PaceCoach.evaluate(null, 300.0, 150).cue)
        // heart-rate cap overrides an on-target pace
        val capped = t.copy(hrMax = 145)
        assertEquals(Guidance(Cue.SLOW_DOWN, 5, CueBasis.HEART_RATE), PaceCoach.evaluate(capped, 305.0, 150))
        val hrOnly = Target(hrMin = 130, hrMax = 140)
        assertEquals(Cue.SPEED_UP, PaceCoach.evaluate(hrOnly, null, 120).cue)
        assertEquals(Cue.ON_TARGET, PaceCoach.evaluate(hrOnly, null, 135).cue)
        assertEquals(Cue.SLOW_DOWN, PaceCoach.evaluate(hrOnly, null, 150).cue)
    }

    @Test fun smootherGivesStablePace() {
        val s = PaceSmoother()
        assertNull(s.pace())
        for (i in 0..30) s.add(i.toDouble(), i * 3.0) // 3 m/s = 5:33/km
        assertEquals(333.3, s.pace()!!, 0.5)
        for (i in 31..60) s.add(i.toDouble(), 90.0) // standing still
        assertNull(s.pace())
    }

    @Test fun statsAndSync() {
        val plan = PlanGenerator.generate(Profile(raceDate = "2027-04-25"), today)
        val w = plan.weeks[0].workouts.first()
        val monday = Stats.monday(w.localDate)
        val samples = (0..600).map { Sample(t = it * 5, d = it * 15f, hr = 140) }
        val run = RunRecord(
            id = "r1", startEpochMs = w.localDate.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            durationS = 3000, distanceM = 9000, workoutId = w.id, samples = samples,
        )
        val later = monday.plusDays(6)
        assertEquals(WorkoutStatus.DONE, Stats.status(w, plan, listOf(run), emptyMap(), later))
        val weekly = Stats.weekly(listOf(run), plan, later, 4)
        assertEquals(9000, weekly.last().distanceM)
        assertTrue(weekly.last().plannedM > 0)
        assertEquals(1, Stats.weekStreak(listOf(run), later))
        val splits = Stats.splits(samples, 1000.0)
        assertEquals(9, splits.size)
        assertTrue(splits.all { it.durationS in 330..336 }, splits.toString())

        val round = SyncProtocol.decodeRun(SyncProtocol.encodeRun(run))
        assertEquals(run, round)
        val payload = WatchPayload(workouts = plan.weeks[0].workouts, hrZones = PaceCalc.hrZones(35, null))
        val text = SyncProtocol.json.encodeToString(WatchPayload.serializer(), payload)
        assertEquals(payload, SyncProtocol.json.decodeFromString(WatchPayload.serializer(), text))
    }

    @Test fun backupRoundTrip() {
        val profile = Profile(raceDate = "2027-04-25", method = TrainingMethod.VDOT, units = Units.MI)
        val plan = PlanGenerator.generate(profile, today)
        val run = RunRecord(id = "r1", startEpochMs = 1_800_000_000_000, durationS = 1800, distanceM = 5000,
            samples = listOf(Sample(0, 0f, hr = 120, lat = 52.1, lon = 4.3), Sample(5, 14f, hr = 125)))
        val backup = Backup(
            exportedEpochMs = 123, profile = profile, plan = plan, completions = mapOf("a" to Completion.SKIPPED),
            runs = listOf(run), reminders = ReminderSettings(hour = 6, eveningBefore = true), watchSettings = WatchSettings(cueIntervalS = 30),
        )
        assertEquals(backup, Backup.decode(backup.encode()))
        assertTrue(runCatching { Backup.decode("not a backup".toByteArray()) }.isFailure)
        assertTrue(runCatching { Backup.decode(SyncProtocol.gzip("{\"version\":99}")) }.isFailure)
    }
}
