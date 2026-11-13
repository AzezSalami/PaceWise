package app.pacewise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pacewise.data.Repository
import app.pacewise.shared.Fmt
import app.pacewise.shared.PaceCalc
import app.pacewise.shared.Stats
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(repo: Repository) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val plan = repo.plan.collectAsStateWithLifecycle().value ?: return
    val runs by repo.runs.collectAsStateWithLifecycle()
    val completions by repo.completions.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val u = profile.units

    Scaffold(topBar = { TopAppBar(title = { Text("Progress", fontWeight = FontWeight.Bold) }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (runs.isEmpty()) AppCard {
                Text("No runs yet", fontWeight = FontWeight.SemiBold)
                Text(
                    "Finish a run on your watch and it will show up here with your weekly distance, pace trend and race prediction.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- headline numbers
            val totalM = runs.sumOf { it.distanceM }
            val totalS = runs.sumOf { it.durationS }
            val longest = Stats.longest(runs)
            val adherence = Stats.adherence(plan, runs, completions, today)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Total distance", Fmt.distance(totalM, u, 0), Modifier.weight(1f), u.label)
                StatTile("Runs", "${runs.size}", Modifier.weight(1f))
                StatTile("Time running", Fmt.durationWords(totalS), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Longest run", longest?.let { Fmt.distance(it.distanceM, u) } ?: "–", Modifier.weight(1f), u.label)
                StatTile("Week streak", "${Stats.weekStreak(runs, today)}", Modifier.weight(1f), "wk")
                StatTile("Avg pace", Fmt.pace(if (totalM > 0) totalS / (totalM / 1000.0) else null, u), Modifier.weight(1f), Fmt.paceUnit(u))
            }

            // --- adherence
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Plan followed", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${adherence.percent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { adherence.percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                Text(
                    if (adherence.total == 0) "Your first session is still ahead."
                    else "${adherence.done} done · ${adherence.skipped} skipped · ${adherence.missed} missed. " +
                        if (adherence.percent >= 80) "Consistency like this is what gets you to the finish." else "Don't chase missed sessions; just pick up with the next one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- weekly distance
            val weeks = Stats.weekly(runs, plan, today, 10)
            AppCard {
                Text("Weekly distance (${u.label})", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)) {
                    Dot(MaterialTheme.colorScheme.primary, 8)
                    Text(" You ran   ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("- - ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Planned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WeeklyBars(
                    actual = weeks.map { (it.distanceM / u.metersPerUnit).toFloat() },
                    planned = weeks.map { (it.plannedM / u.metersPerUnit).toFloat() },
                    labels = weeks.map { it.start.format(java.time.format.DateTimeFormatter.ofPattern("d/M")) },
                )
                val w = weeks.last()
                Text(
                    "This week: ${Fmt.distanceWithUnit(w.distanceM, u)} of ${Fmt.distanceWithUnit(w.plannedM, u, 0)} planned",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- prediction
            AppCard {
                Text("Race prediction", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val vdot = Stats.estimateVdot(runs, today)
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Your goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Fmt.duration(profile.goalTimeS), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Current form", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            vdot?.let { Fmt.duration(PaceCalc.predictTime(it)) } ?: "–",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    if (vdot == null) "Needs a run of 3 km or more in the last eight weeks."
                    else "From your strongest run of the last eight weeks. Easy runs under-sell your fitness, so this firms up after tempo sessions, tests and long runs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- trends
            val recent = runs.take(20).reversed()
            if (recent.size >= 3) {
                AppCard {
                    Text("Average pace, last ${recent.size} runs", fontWeight = FontWeight.SemiBold)
                    val paces = recent.map { it.avgPace?.toFloat() }
                    val present = paces.filterNotNull()
                    LineChart(
                        paces, invert = true,
                        topLabel = "fastest ${Fmt.pace(present.min().toDouble(), u)} ${Fmt.paceUnit(u)}",
                        bottomLabel = "slowest ${Fmt.pace(present.max().toDouble(), u)} ${Fmt.paceUnit(u)}",
                        startLabel = recent.first().localDate.format(ShortFmt), endLabel = recent.last().localDate.format(ShortFmt),
                    )
                }
                val hrs = recent.map { it.avgHr?.toFloat() }
                if (hrs.count { it != null } >= 3) AppCard {
                    Text("Average heart rate", fontWeight = FontWeight.SemiBold)
                    val present = hrs.filterNotNull()
                    LineChart(
                        hrs, color = MaterialTheme.colorScheme.secondary,
                        topLabel = "${present.max().toInt()} bpm", bottomLabel = "${present.min().toInt()} bpm",
                        startLabel = recent.first().localDate.format(ShortFmt), endLabel = recent.last().localDate.format(ShortFmt),
                    )
                    Text(
                        "The same pace at a lower heart rate is one of the clearest signs of improving fitness.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val coached = recent.mapNotNull { it.onTargetPct }
                if (coached.isNotEmpty()) AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Time on target", fontWeight = FontWeight.SemiBold)
                            Text("How much of your coached running was inside the target pace or heart rate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${coached.average().toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
