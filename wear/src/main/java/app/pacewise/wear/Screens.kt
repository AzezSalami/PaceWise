package app.pacewise.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import app.pacewise.shared.Cue
import app.pacewise.shared.CueBasis
import app.pacewise.shared.Fmt
import app.pacewise.shared.RunRecord
import app.pacewise.shared.Segment
import app.pacewise.shared.StepKind
import app.pacewise.shared.Units
import app.pacewise.shared.WatchPayload
import app.pacewise.shared.Workout
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val Green = Color(0xFF51CF66)
val Blue = Color(0xFF4DABF7)
val Orange = Color(0xFFFF922B)
val Dim = Color(0xFF9AA5A1)

val WearColors = Colors(
    primary = Color(0xFF38D9A9), primaryVariant = Color(0xFF12B886), onPrimary = Color(0xFF00281E),
    secondary = Color(0xFFFFA94D), onSecondary = Color(0xFF3D1A00),
    surface = Color(0xFF1B2421), onSurface = Color(0xFFE9F0ED), onSurfaceVariant = Dim,
    background = Color.Black, onBackground = Color(0xFFE9F0ED),
)

private val DayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

private fun length(w: Workout, u: Units) =
    if (w.plannedDistanceM > 0) Fmt.distanceWithUnit(w.plannedDistanceM, u) else Fmt.durationWords(w.plannedDurationS)

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(payload: WatchPayload, pending: Int, message: String?, onPreview: (Workout) -> Unit, onFreeRun: () -> Unit) {
    val today = LocalDate.now()
    val open = payload.workouts.filter { it.id !in payload.doneIds }
    val todays = open.firstOrNull { it.date == today.toString() }
    val upcoming = open.filter { it.localDate.isAfter(today) }.take(3)
    val state = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }, vignette = { Vignette(VignettePosition.TopAndBottom) }, positionIndicator = { PositionIndicator(state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("PaceWise", fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary) }
            if (message != null) item { Text(message, textAlign = TextAlign.Center, color = Orange, style = MaterialTheme.typography.caption1) }

            if (todays != null) item {
                Chip(
                    onClick = { onPreview(todays) }, modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(todays.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                    secondaryLabel = { Text("Today · ${length(todays, payload.units)}") },
                )
            } else item {
                Text(
                    if (payload.updatedEpochMs == 0L) "Open PaceWise on your phone to load your plan" else "No session planned today",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1, color = Dim,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Chip(
                    onClick = onFreeRun, modifier = Modifier.fillMaxWidth(),
                    colors = if (todays == null) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    label = { Text("Free run", fontWeight = FontWeight.Bold) },
                    secondaryLabel = { Text("No targets, just record") },
                )
            }
            if (upcoming.isNotEmpty()) {
                item { Text("Coming up", style = MaterialTheme.typography.caption1, color = Dim, modifier = Modifier.padding(top = 6.dp)) }
                items(upcoming) { w ->
                    Chip(
                        onClick = { onPreview(w) }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("${w.localDate.format(DayFmt)} · ${length(w, payload.units)}") },
                    )
                }
            }
            item {
                Text(
                    when (pending) {
                        0 -> "All runs synced"
                        1 -> "1 run waiting for your phone"
                        else -> "$pending runs waiting for your phone"
                    },
                    style = MaterialTheme.typography.caption2, color = if (pending == 0) Dim else Orange, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ preview

@Composable
fun PreviewScreen(w: Workout, u: Units, onStart: () -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(w.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            item { Text("${length(w, u)} · about ${Fmt.durationWords(w.plannedDurationS)}", style = MaterialTheme.typography.caption1, color = Dim) }
            item {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = ButtonDefaults.primaryButtonColors()) {
                    Text("START", fontWeight = FontWeight.Bold)
                }
            }
            items(w.steps) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (s.kind == StepKind.RUN) MaterialTheme.colors.primary else Dim))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(s.label, style = MaterialTheme.typography.caption1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (Fmt.target(s.target, u) ?: s.runWalk?.let { "run/walk" })?.let { Text(it, style = MaterialTheme.typography.caption2, color = Dim) }
                    }
                    Text(Fmt.stepLength(s, u), style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold)
                }
            }
            item { Text(w.description, style = MaterialTheme.typography.caption2, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
        }
    }
}

// ------------------------------------------------------------------ run

@Composable
fun RunScreen(ui: RunUi, payload: WatchPayload) {
    val pager = rememberPagerState(initialPage = 0) { 3 }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> CoachPage(ui, payload.units)
                1 -> StatsPage(ui, payload)
                else -> ControlsPage(ui)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Color.White else Color(0xFF55605C))) }
        }
    }
}

/** The glanceable page: current pace, what to do about it, and how much of the step is left. */
@Composable
private fun CoachPage(ui: RunUi, u: Units) {
    val es = ui.engine
    val g = ui.guidance
    val paused = ui.phase == RunPhase.PAUSED
    val walking = es.segment == Segment.WALK
    val accent = when {
        paused -> Dim
        walking -> Blue
        g.cue == Cue.SPEED_UP -> Blue
        g.cue == Cue.SLOW_DOWN -> Orange
        g.cue == Cue.ON_TARGET -> Green
        else -> Color.White
    }
    val progress = if (es.step != null) es.stepProgress else es.totalProgress

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // progress ring round the edge of the screen, coloured by the current cue
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0xFF26302D), -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (progress > 0f) drawArc(accent, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 18.dp)) {
            // step and what's left of it
            val stepLine = when {
                ui.phase == RunPhase.STARTING -> "Starting…"
                es.finished -> "Workout complete"
                es.step != null -> es.step!!.label
                else -> "Free run"
            }
            Text(stepLine, style = MaterialTheme.typography.caption1, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = when {
                es.stepRemainingM != null -> (if (es.stepRemainingM!! < 1000 && u == Units.KM) "${es.stepRemainingM} m" else Fmt.distanceWithUnit(es.stepRemainingM!!, u, 2)) + " left"
                es.stepRemainingS != null -> Fmt.duration(es.stepRemainingS!!) + " left"
                else -> Fmt.distanceWithUnit(ui.distanceM, u, 2)
            }
            Text(left, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold)

            // the big number
            if (walking) {
                Text("WALK", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Blue)
                Text(Fmt.duration(es.segmentRemainingS ?: 0), style = MaterialTheme.typography.title2)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.pace(ui.pace, u), fontSize = 46.sp, fontWeight = FontWeight.Black, color = accent)
                    Text(Fmt.paceUnit(u), style = MaterialTheme.typography.caption2, color = Dim, modifier = Modifier.padding(bottom = 9.dp, start = 2.dp))
                }
                val unit = if (g.basis == CueBasis.HEART_RATE) "bpm" else "s"
                val cueText = when {
                    paused -> if (ui.autoPaused) "AUTO-PAUSED" else "PAUSED"
                    g.cue == Cue.SPEED_UP -> "▲ SPEED UP  ${g.delta}$unit"
                    g.cue == Cue.SLOW_DOWN -> if (g.basis == CueBasis.HEART_RATE) "▼ EASE OFF  +${g.delta}bpm" else "▼ SLOW DOWN  ${g.delta}s"
                    g.cue == Cue.ON_TARGET -> if (g.basis == CueBasis.HEART_RATE) "✓ IN ZONE" else "✓ ON PACE"
                    es.segment == Segment.RUN -> "RUN  ${Fmt.duration(es.segmentRemainingS ?: 0)}"
                    es.step?.kind == StepKind.RECOVER -> "RECOVER"
                    else -> ""
                }
                Text(cueText, fontSize = 15.sp, fontWeight = FontWeight.Black, color = accent, maxLines = 1)
            }

            // target and heart rate
            Spacer(Modifier.height(2.dp))
            val target = Fmt.target(es.activeTarget, u)
            Text(
                listOfNotNull(target?.let { "target $it" }, if (target == null) Fmt.duration(ui.activeS) else null).joinToString(" "),
                style = MaterialTheme.typography.caption2, color = Dim, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♥ ${ui.hr ?: "--"}", style = MaterialTheme.typography.caption1, color = Color(0xFFFF8787), fontWeight = FontWeight.Bold)
                if (!ui.gpsReady && ui.phase != RunPhase.PAUSED) Text("  GPS…", style = MaterialTheme.typography.caption2, color = Orange)
                else if (es.segment == Segment.RUN && g.cue != Cue.NONE) Text("  run ${Fmt.duration(es.segmentRemainingS ?: 0)}", style = MaterialTheme.typography.caption2, color = Dim)
            }
        }
    }
}

@Composable
private fun StatsPage(ui: RunUi, payload: WatchPayload) {
    val u = payload.units
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        Row {
            Stat("TIME", Fmt.duration(ui.activeS), Modifier.weight(1f))
            Stat(u.label.uppercase(), Fmt.distance(ui.distanceM, u, 2), Modifier.weight(1f))
        }
        Row {
            Stat("AVG PACE", Fmt.pace(ui.avgPace, u), Modifier.weight(1f))
            val zone = ui.hr?.let { hr -> payload.hrZones?.zone(hr) }
            Stat("HEART" + (zone?.let { " Z$it" } ?: ""), ui.hr?.toString() ?: "--", Modifier.weight(1f), Color(0xFFFF8787))
        }
        Row {
            Stat("CADENCE", ui.cadence?.toString() ?: "--", Modifier.weight(1f))
            if (ui.elevGainM != null && ui.elevGainM > 0) Stat("CLIMB m", "${ui.elevGainM}", Modifier.weight(1f))
            else Stat("KCAL", ui.calories?.toString() ?: "--", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = Color.White) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.caption2, color = Dim)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun ControlsPage(ui: RunUi) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val paused = ui.phase == RunPhase.PAUSED
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (confirm) {
            Text("Finish this run?", style = MaterialTheme.typography.caption1)
            Chip(onClick = { ExerciseService.send(ctx, ExerciseService.ACTION_STOP) }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.primaryChipColors(), label = { Text("Save run", fontWeight = FontWeight.Bold) })
            Chip(onClick = { ExerciseService.send(ctx, ExerciseService.ACTION_DISCARD) }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(), label = { Text("Discard", color = Orange) })
            Chip(onClick = { confirm = false }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(), label = { Text("Keep running") })
        } else {
            Chip(
                onClick = { ExerciseService.send(ctx, if (paused) ExerciseService.ACTION_RESUME else ExerciseService.ACTION_PAUSE) },
                modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.primaryChipColors(),
                label = { Text(if (paused) "Resume" else "Pause", fontWeight = FontWeight.Bold) },
            )
            if (ui.engine.step != null) Chip(
                onClick = { ExerciseService.send(ctx, ExerciseService.ACTION_SKIP) }, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Next step") },
                secondaryLabel = { Text(ui.engine.nextStep?.label ?: "ends the workout", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            Chip(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(), label = { Text("Finish", color = Orange, fontWeight = FontWeight.Bold) })
        }
    }
}

// ------------------------------------------------------------------ summary

@Composable
fun SummaryScreen(r: RunRecord, u: Units, pending: Int, onDone: () -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("Well done!", fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary) }
            item { Text(r.title, style = MaterialTheme.typography.caption1, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Stat(u.label.uppercase(), Fmt.distance(r.distanceM, u, 2), Modifier.weight(1f))
                    Stat("TIME", Fmt.duration(r.durationS), Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Stat("AVG PACE", Fmt.pace(r.avgPace, u), Modifier.weight(1f))
                    Stat("AVG HEART", r.avgHr?.toString() ?: "--", Modifier.weight(1f), Color(0xFFFF8787))
                }
            }
            if (r.onTargetPct != null) item {
                Text("${r.onTargetPct}% of the time on target", style = MaterialTheme.typography.caption1, color = Green, textAlign = TextAlign.Center)
            }
            item {
                Text(
                    if (pending == 0) "Synced to your phone" else "Saved. It will sync when your phone is nearby.",
                    style = MaterialTheme.typography.caption2, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.primaryButtonColors()) { Text("Done", fontWeight = FontWeight.Bold) } }
        }
    }
}
