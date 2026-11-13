package app.pacewise.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pacewise.data.Repository
import app.pacewise.shared.Fmt
import app.pacewise.shared.Stats
import app.pacewise.shared.Workout
import app.pacewise.shared.WorkoutStatus
import app.pacewise.sync.PhoneSync
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlanScreen(repo: Repository, onOpenRun: (String) -> Unit) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val plan = repo.plan.collectAsStateWithLifecycle().value ?: return
    val runs by repo.runs.collectAsStateWithLifecycle()
    val completions by repo.completions.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val u = profile.units
    var sheet by remember { mutableStateOf<Workout?>(null) }
    var showPaces by remember { mutableStateOf(false) }

    // Open on the current week: each week contributes a header plus its workouts.
    val currentWeek = plan.weekOf(today)?.index ?: 0
    val startIndex = plan.weeks.take(currentWeek).sumOf { it.workouts.size + 1 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Plan", fontWeight = FontWeight.Bold)
                        Text(plan.method.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = { showPaces = true }) { Icon(Icons.Default.Speed, "Training paces") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            plan.weeks.forEach { week ->
                stickyHeader(key = "h${week.index}") {
                    val start = LocalDate.parse(week.startDate)
                    val isCurrent = week.index == currentWeek && plan.weekOf(today) != null
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Week ${week.index + 1} · ${week.phase.title}" + if (isCurrent) " · now" else "",
                                fontWeight = FontWeight.Bold, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                "${start.format(ShortFmt)} – ${start.plusDays(6).format(ShortFmt)} · ${week.focus}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Fmt.distanceWithUnit(week.plannedDistanceM, u, 0), fontWeight = FontWeight.SemiBold)
                    }
                }
                items(week.workouts, key = { it.id }) { w ->
                    val st = Stats.status(w, plan, runs, completions, today)
                    Row(
                        Modifier.fillMaxWidth().clickable { sheet = w }
                            .background(if (st == WorkoutStatus.TODAY) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(w.type.color(), 12)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.title, fontWeight = FontWeight.Medium)
                            Text(
                                relativeDay(w.localDate, today) + if (w.plannedDistanceM > 0) " · ${Fmt.distanceWithUnit(w.plannedDistanceM, u)}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (st) {
                            WorkoutStatus.DONE -> Icon(Icons.Default.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            WorkoutStatus.SKIPPED -> Text("Skipped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            WorkoutStatus.MISSED -> Icon(Icons.Default.RemoveCircleOutline, "Missed", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                            WorkoutStatus.TODAY -> Text("Today", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    sheet?.let { w ->
        WorkoutSheet(
            w, Stats.status(w, plan, runs, completions, today), Stats.runFor(w, runs), u,
            onMark = { c -> repo.setCompletion(w.id, c); PhoneSync.pushPlan(ctx) },
            onOpenRun = { id -> sheet = null; onOpenRun(id) }, onDismiss = { sheet = null },
        )
    }
    if (showPaces) AlertDialog(
        onDismissRequest = { showPaces = false },
        confirmButton = { TextButton(onClick = { showPaces = false }) { Text("Close") } },
        title = { Text("Your training paces") },
        text = {
            Column {
                Text("Based on your ${Fmt.duration(profile.goalTimeS)} goal (fitness score ${plan.paces.vdot}).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                PaceTable(plan.paces, u)
            }
        },
    )
}
