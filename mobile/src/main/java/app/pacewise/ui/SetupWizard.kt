package app.pacewise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pacewise.shared.Fmt
import app.pacewise.shared.Level
import app.pacewise.shared.PaceCalc
import app.pacewise.shared.PlanGenerator
import app.pacewise.shared.Profile
import app.pacewise.shared.TrainingMethod
import app.pacewise.shared.Units
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

private fun recommended(level: Level): Set<TrainingMethod> = when (level) {
    Level.BEGINNER -> setOf(TrainingMethod.CLASSIC, TrainingMethod.RUN_WALK, TrainingMethod.HEART_RATE)
    Level.INTERMEDIATE -> setOf(TrainingMethod.CLASSIC, TrainingMethod.POLARIZED_80_20, TrainingMethod.VDOT, TrainingMethod.THREE_QUALITY)
    Level.ADVANCED -> setOf(TrainingMethod.HIGH_MILEAGE_LT, TrainingMethod.VDOT, TrainingMethod.CUMULATIVE_FATIGUE, TrainingMethod.THRESHOLD_FOCUS)
}

/** Four friendly steps: race, runner, method, schedule. Also used to change an existing plan. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetupWizard(initial: Profile?, onCancel: (() -> Unit)?, restore: (@Composable () -> Unit)? = null, onDone: (Profile) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var p by remember {
        mutableStateOf(initial ?: Profile(raceDate = LocalDate.now().plusWeeks(18).with(DayOfWeek.SUNDAY).toString()))
    }
    val titles = listOf("Your race", "About you", "Training method", "Your week")

    BackHandler(enabled = step > 0 || onCancel != null) { if (step > 0) step-- else onCancel?.invoke() }

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            // Keep the buttons above the phone's back/home/recents bar.
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0 || onCancel != null) OutlinedButton(onClick = { if (step > 0) step-- else onCancel?.invoke() }, modifier = Modifier.weight(1f)) {
                    Text(if (step > 0) "Back" else "Cancel")
                }
                Button(onClick = { if (step < 3) step++ else onDone(p) }, modifier = Modifier.weight(2f)) {
                    Text(if (step < 3) "Continue" else if (initial == null) "Create my plan" else "Rebuild my plan")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(titles[step], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            when (step) {
                0 -> {
                    restore?.invoke()
                    RaceStep(p) { p = it }
                }
                1 -> RunnerStep(p) { p = it }
                2 -> MethodStep(p) { m ->
                    p = p.copy(method = m, daysPerWeek = m.defaultDays, planWeeks = m.defaultWeeks)
                }
                3 -> ScheduleStep(p) { p = it }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RaceStep(p: Profile, set: (Profile) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showHelper by rememberSaveable { mutableStateOf(false) }
    val race = LocalDate.parse(p.raceDate)

    OutlinedTextField(p.raceName, { set(p.copy(raceName = it)) }, label = { Text("Race name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Event, null); Spacer(Modifier.width(8.dp))
        Text("Race day: ${race.format(LongDayFmt)} ${race.year}")
    }
    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.now(), race)
    Text(
        if (weeks < 8) "That's only $weeks weeks away. The plan will be shortened and the long run built carefully."
        else "$weeks weeks from now.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )

    Spacer(Modifier.height(24.dp))
    Stepper(
        value = Fmt.duration(p.goalTimeS).dropLast(3), label = "Goal finish time (h:mm)",
        onMinus = { set(p.copy(goalTimeS = (p.goalTimeS - 300).coerceAtLeast(2 * 3600 + 15 * 60))) },
        onPlus = { set(p.copy(goalTimeS = (p.goalTimeS + 300).coerceAtMost(7 * 3600))) },
        modifier = Modifier.fillMaxWidth(),
    )
    val m = PaceCalc.pacesForMarathon(p.goalTimeS).marathon.mid
    Text(
        "Marathon pace ${Fmt.pace(m, p.units)} ${Fmt.paceUnit(p.units)}",
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { showHelper = !showHelper }, modifier = Modifier.fillMaxWidth()) {
        Text(if (showHelper) "Hide" else "Not sure? Estimate from a recent race")
    }
    AnimatedVisibility(showHelper) {
        var dist by rememberSaveable { mutableIntStateOf(10000) }
        var min by rememberSaveable { mutableStateOf("") }
        var sec by rememberSaveable { mutableStateOf("") }
        AppCard {
            Text("Recent race or hard effort", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5000 to "5 km", 10000 to "10 km", 21097 to "Half marathon").forEach { (d, l) ->
                    FilterChip(dist == d, { dist = d }, label = { Text(l) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(min, { min = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(sec, { sec = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            val t = (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
            if (t > 600) {
                val predicted = PaceCalc.predictTime(PaceCalc.vdot(dist.toDouble(), t.toDouble()))
                val rounded = ((predicted + 150) / 300 * 300).coerceIn(2 * 3600 + 15 * 60, 7 * 3600)
                Spacer(Modifier.height(8.dp))
                Text("That suggests a marathon of about ${Fmt.duration(predicted)} with full training.")
                Button(onClick = { set(p.copy(goalTimeS = rounded)); showHelper = false }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Use ${Fmt.duration(rounded).dropLast(3)} as my goal")
                }
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = race.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (d.isAfter(LocalDate.now())) set(p.copy(raceDate = d.toString()))
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunnerStep(p: Profile, set: (Profile) -> Unit) {
    Text("Where are you now?", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Level.entries.forEach { l ->
        SelectCard(selected = p.level == l, title = l.title, subtitle = l.hint, onClick = { set(p.copy(level = l)) })
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(12.dp))
    Stepper(
        value = "${p.age}", label = "Age",
        onMinus = { set(p.copy(age = (p.age - 1).coerceAtLeast(16))) },
        onPlus = { set(p.copy(age = (p.age + 1).coerceAtMost(85))) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    var hr by rememberSaveable { mutableStateOf(p.maxHr?.toString().orEmpty()) }
    OutlinedTextField(
        hr, { v -> hr = v.filter(Char::isDigit).take(3); set(p.copy(maxHr = hr.toIntOrNull()?.takeIf { it in 120..230 })) },
        label = { Text("Max heart rate (optional)") }, singleLine = true,
        supportingText = { Text("Leave empty to estimate it from your age: ${PaceCalc.hrZones(p.age, null).maxHr} bpm") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Text("Distances in", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(p.units == Units.KM, { set(p.copy(units = Units.KM)) }, label = { Text("Kilometres") })
        FilterChip(p.units == Units.MI, { set(p.copy(units = Units.MI)) }, label = { Text("Miles") })
    }
}

@Composable
private fun MethodStep(p: Profile, pick: (TrainingMethod) -> Unit) {
    Text(
        "Every method gets you to the start line. Pick the one that fits your life; you can switch later in Settings.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    val rec = recommended(p.level)
    TrainingMethod.entries.sortedByDescending { it in rec }.forEach { m ->
        val selected = p.method == m
        SelectCard(
            selected = selected, title = m.title, subtitle = m.tagline,
            badge = if (m in rec) "Good fit for you" else null,
            onClick = { pick(m) },
        ) {
            if (selected) {
                Spacer(Modifier.height(8.dp))
                Text(m.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text("Best for: ${m.bestFor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (m.minDays == m.maxDays) "${m.minDays} runs a week · ${m.defaultWeeks} weeks"
                    else "${m.minDays}–${m.maxDays} runs a week · ${m.defaultWeeks} weeks",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleStep(p: Profile, set: (Profile) -> Unit) {
    val m = p.method
    Text("Runs per week", fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (m.minDays..m.maxDays).forEach { d ->
            FilterChip(p.effectiveDays == d, { set(p.copy(daysPerWeek = d)) }, label = { Text("$d") })
        }
    }
    if (m.minDays == m.maxDays) Text("${m.title} always uses ${m.minDays} runs a week.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Spacer(Modifier.height(16.dp))
    Text("Long run day", fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { d ->
            FilterChip(p.longRunDay == d.value, { set(p.copy(longRunDay = d.value)) }, label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Plan length: ${p.planWeeks} weeks", fontWeight = FontWeight.SemiBold)
    Slider(
        value = p.planWeeks.toFloat(), onValueChange = { set(p.copy(planWeeks = it.toInt())) },
        valueRange = 8f..24f, steps = 15,
    )
    val plan = remember(p) { PlanGenerator.generate(p) }
    val start = LocalDate.parse(plan.weeks.first().startDate)
    val peakWeek = plan.weeks.maxBy { it.plannedDistanceM }
    val longest = plan.allWorkouts().filter { it.type == app.pacewise.shared.WorkoutType.LONG }.maxOfOrNull { it.plannedDistanceM } ?: 0
    AppCard {
        Text("Your plan at a glance", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("${plan.weeks.size} weeks, starting ${if (start.isAfter(LocalDate.now())) start.format(LongDayFmt) else "this week"}")
        Text("Biggest week: ${Fmt.distanceWithUnit(peakWeek.plannedDistanceM, p.units, 0)} · longest run: ${Fmt.distanceWithUnit(longest, p.units, 0)}")
        Spacer(Modifier.height(8.dp))
        PaceTable(plan.paces, p.units)
    }
}

@Composable
fun PaceTable(paces: app.pacewise.shared.Paces, units: Units) {
    val rows = listOf(
        "Easy" to paces.easy, "Long run" to paces.long, "Marathon" to paces.marathon,
        "Threshold" to paces.threshold, "Interval" to paces.interval, "Repetition" to paces.repetition,
    )
    Column {
        rows.forEach { (name, r) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Fmt.paceRange(r.fast, r.slow, units)} ${Fmt.paceUnit(units)}", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SelectCard(
    selected: Boolean, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (badge != null) Text(badge.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            extra()
        }
    }
}
