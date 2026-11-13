package app.pacewise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pacewise.data.Repository
import app.pacewise.shared.Completion
import app.pacewise.shared.Fmt
import app.pacewise.shared.RunRecord
import app.pacewise.shared.Stats
import app.pacewise.shared.Units
import app.pacewise.shared.Workout
import app.pacewise.shared.WorkoutStatus
import app.pacewise.sync.PhoneSync
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(repo: Repository, onSettings: () -> Unit, onOpenRun: (String) -> Unit, onOpenPlan: () -> Unit) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val plan = repo.plan.collectAsStateWithLifecycle().value ?: return
    val runs by repo.runs.collectAsStateWithLifecycle()
    val completions by repo.completions.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val u = profile.units
    var sheet by remember { mutableStateOf<Workout?>(null) }
    var watch by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { watch = PhoneSync.connectedWatch(ctx) }

    fun statusOf(w: Workout) = Stats.status(w, plan, runs, completions, today)
    fun mark(w: Workout, c: Completion?) { repo.setCompletion(w.id, c); PhoneSync.pushPlan(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val hour = LocalTime.now().hour
                    Text(if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening", fontWeight = FontWeight.Bold)
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- countdown
            val race = LocalDate.parse(plan.raceDate)
            val start = LocalDate.parse(plan.weeks.first().startDate)
            val daysLeft = ChronoUnit.DAYS.between(today, race)
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.raceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${race.format(DayFmt)} · goal ${Fmt.duration(profile.goalTimeS)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            when {
                                daysLeft < 0 -> "Done"
                                daysLeft == 0L -> "Today!"
                                daysLeft < 15 -> "$daysLeft"
                                else -> "${daysLeft / 7}"
                            },
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        )
                        if (daysLeft > 0) Text(if (daysLeft < 15) "days to go" else "weeks to go", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val total = ChronoUnit.DAYS.between(start, race).coerceAtLeast(1)
                val done = ChronoUnit.DAYS.between(start, today).coerceIn(0, total)
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                val week = plan.weekOf(today)
                Text(
                    week?.let { "Week ${it.index + 1} of ${plan.weeks.size} · ${it.phase.title} · ${it.focus}" }
                        ?: if (today.isBefore(start)) "Your plan starts ${start.format(LongDayFmt)}. Until then, easy runs keep you ticking over."
                        else "Plan complete. Recover well!",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- today's session
            val w = plan.workoutOn(today)
            if (w != null) {
                val st = statusOf(w)
                AppCard(onClick = { sheet = w }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeBadge(w.type)
                        Spacer(Modifier.weight(1f))
                        Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(w.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(workoutLength(w, u), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(w.description, style = MaterialTheme.typography.bodyMedium)
                    if (w.steps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        StepList(w.steps.take(4), u)
                        if (w.steps.size > 4) Text("+ ${w.steps.size - 4} more steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    when (st) {
                        WorkoutStatus.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text("  Done. Nice work!", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        WorkoutStatus.SKIPPED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Skipped", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { mark(w, null) }) { Text("Undo") }
                        }
                        else -> {
                            if (w.type.isRun) Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Watch, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Ready on your watch. Open PaceWise there and tap Start.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { mark(w, Completion.SKIPPED) }, modifier = Modifier.weight(1f)) { Text("Skip") }
                                Button(onClick = { mark(w, Completion.DONE) }, modifier = Modifier.weight(1f)) { Text("Mark done") }
                            }
                        }
                    }
                }
            } else {
                val next = plan.allWorkouts().firstOrNull { it.localDate.isAfter(today) }
                AppCard(onClick = next?.let { n -> { sheet = n } }) {
                    Text("Rest day", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Rest is when the training turns into fitness. Enjoy it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (next != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(next.type.color())
                            Text("  Next: ${next.title} · ${relativeDay(next.localDate)}", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // --- this week
            plan.weekOf(today)?.let { week ->
                val weekStart = LocalDate.parse(week.startDate)
                val weekRuns = runs.filter { !it.localDate.isBefore(weekStart) && it.localDate.isBefore(weekStart.plusDays(7)) }
                AppCard(onClick = onOpenPlan) {
                    Row {
                        Text("This week", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "${Fmt.distance(weekRuns.sumOf { it.distanceM }, u)} / ${Fmt.distanceWithUnit(week.plannedDistanceM, u, 0)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        for (i in 0..6) {
                            val d = weekStart.plusDays(i.toLong())
                            val wk = week.workouts.firstOrNull { it.date == d.toString() }
                            DayCell(d, wk, wk?.let(::statusOf), d == today, Modifier.weight(1f)) { wk?.let { sheet = it } }
                        }
                    }
                }
            }

            // --- watch + last run
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Watch, null, tint = if (watch != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(watch?.let { "$it connected" } ?: "No watch connected right now", fontWeight = FontWeight.Medium)
                        Text(
                            if (watch != null) "Workouts and runs sync automatically."
                            else "Runs are kept on the watch and sync when it's back in range.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            runs.firstOrNull()?.let { r ->
                SectionTitle("Latest run")
                RunRow(r, u) { onOpenRun(r.id) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    sheet?.let { w ->
        WorkoutSheet(
            w, statusOf(w), Stats.runFor(w, runs), u,
            onMark = { c -> mark(w, c) }, onOpenRun = { id -> sheet = null; onOpenRun(id) }, onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun DayCell(date: LocalDate, w: Workout?, status: WorkoutStatus?, isToday: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = w != null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        val color = w?.type?.color() ?: Color.Transparent
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (status == WorkoutStatus.DONE) color else Color.Transparent)
                .border(2.dp, if (w == null) MaterialTheme.colorScheme.outlineVariant else if (status == WorkoutStatus.MISSED || status == WorkoutStatus.SKIPPED) MaterialTheme.colorScheme.outline else color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                WorkoutStatus.DONE -> Icon(Icons.Default.Check, "Done", tint = Color.White, modifier = Modifier.size(18.dp))
                WorkoutStatus.MISSED, WorkoutStatus.SKIPPED -> Icon(Icons.Default.Close, "Missed", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                null -> {}
                else -> Text(
                    if (w!!.plannedDistanceM > 0) "${(w.plannedDistanceM / 1000.0).toInt()}" else "×",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun RunRow(r: RunRecord, u: Units, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(r.type.color(), 12)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, fontWeight = FontWeight.SemiBold)
                Text(relativeDay(r.localDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.distanceWithUnit(r.distanceM, u, 2), fontWeight = FontWeight.Bold)
                Text(
                    "${Fmt.duration(r.durationS)} · ${Fmt.pace(r.avgPace, u)} ${Fmt.paceUnit(u)}" + (r.avgHr?.let { " · $it bpm" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full detail of a planned session, with done/skip actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSheet(
    w: Workout, status: WorkoutStatus, run: RunRecord?, u: Units,
    onMark: (Completion?) -> Unit, onOpenRun: (String) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(w.type)
                Spacer(Modifier.weight(1f))
                Text(w.localDate.format(LongDayFmt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(w.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(workoutLength(w, u), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(w.description)
            if (w.steps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Steps", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StepList(w.steps, u)
            }
            Spacer(Modifier.height(18.dp))
            if (run != null) {
                Button(onClick = { onOpenRun(run.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("See the run: ${Fmt.distanceWithUnit(run.distanceM, u, 2)} in ${Fmt.duration(run.durationS)}")
                }
            } else when (status) {
                WorkoutStatus.DONE, WorkoutStatus.SKIPPED -> OutlinedButton(onClick = { onMark(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (status == WorkoutStatus.DONE) "Marked done · undo" else "Skipped · undo")
                }
                WorkoutStatus.UPCOMING -> Text(
                    "This session will appear on your watch when it's due.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMark(Completion.SKIPPED); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Skip") }
                    Button(onClick = { onMark(Completion.DONE); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Mark done") }
                }
            }
        }
    }
}
