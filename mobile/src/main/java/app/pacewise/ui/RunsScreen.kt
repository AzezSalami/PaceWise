package app.pacewise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pacewise.data.Repository
import app.pacewise.shared.Fmt
import app.pacewise.shared.RunRecord
import app.pacewise.shared.RunSource
import app.pacewise.shared.Stats
import app.pacewise.shared.Units
import app.pacewise.sync.PhoneSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunsScreen(repo: Repository, onOpenRun: (String) -> Unit) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val runs by repo.runs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Runs", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add run") })
        },
    ) { pad ->
        if (runs.isEmpty()) Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Runs recorded on your watch appear here automatically.\n\nRan without the watch? Add it by hand so your stats stay complete.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else LazyColumn(
            Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(runs, key = { it.id }) { r -> RunRow(r, profile.units) { onOpenRun(r.id) } }
        }
    }

    if (adding) AddRunDialog(profile.units, onDismiss = { adding = false }) { run ->
        repo.saveRun(run)
        PhoneSync.pushPlan(ctx)
        adding = false
    }
}

@Composable
private fun AddRunDialog(u: Units, onDismiss: () -> Unit, onSave: (RunRecord) -> Unit) {
    var dist by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var daysAgo by rememberSaveable { mutableStateOf(0) }
    val d = dist.replace(',', '.').toDoubleOrNull()
    val m = min.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a run") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(dist, { dist = it }, label = { Text("Distance (${u.label})") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(min, { min = it.filter(Char::isDigit) }, label = { Text("Time (minutes)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Stepper(
                    value = relativeDay(LocalDate.now().minusDays(daysAgo.toLong())), label = "When",
                    onMinus = { daysAgo = (daysAgo + 1).coerceAtMost(60) }, onPlus = { daysAgo = (daysAgo - 1).coerceAtLeast(0) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = d != null && d > 0 && m != null && m > 0, onClick = {
                val start = LocalDate.now().minusDays(daysAgo.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onSave(RunRecord(
                    id = "manual-$start-${System.currentTimeMillis() % 100000}", startEpochMs = start, durationS = m!! * 60,
                    distanceM = (d!! * u.metersPerUnit).toInt(), title = "Run", source = RunSource.MANUAL,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(repo: Repository, id: String, onBack: () -> Unit) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val u = profile.units
    var run by remember { mutableStateOf<RunRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        run = withContext(Dispatchers.IO) { repo.loadRun(id) }
        loaded = true
    }
    val r = run

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(r?.title ?: "Run", fontWeight = FontWeight.Bold)
                        if (r != null) Text(r.localDate.format(LongDayFmt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (r != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete run") } },
            )
        },
    ) { pad ->
        if (r == null) {
            if (loaded) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("This run is no longer available.") }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Distance", Fmt.distance(r.distanceM, u, 2), Modifier.weight(1f), u.label)
                StatTile("Time", Fmt.duration(r.durationS), Modifier.weight(1f))
                StatTile("Avg pace", Fmt.pace(r.avgPace, u), Modifier.weight(1f), Fmt.paceUnit(u))
            }
            val extra = listOfNotNull(
                r.avgHr?.let { Triple("Avg heart rate", "$it", "bpm") },
                r.maxHr?.let { Triple("Max heart rate", "$it", "bpm") },
                r.avgCadence?.let { Triple("Cadence", "$it", "spm") },
                r.elevGainM?.let { Triple("Climb", "$it", "m") },
                r.calories?.let { Triple("Energy", "$it", "kcal") },
                r.onTargetPct?.let { Triple("On target", "$it", "%") },
            )
            extra.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (l, v, unit) -> StatTile(l, v, Modifier.weight(1f), unit) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (r.source == RunSource.MANUAL) Text("Added by hand, so there are no charts for this run.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            if (r.samples.count { it.lat != null } >= 2) AppCard {
                Text("Route", fontWeight = FontWeight.SemiBold)
                RouteMap(r.samples)
            }
            val paces = r.samples.map { it.pace?.takeIf { p -> p in 120f..900f } }
            if (paces.count { it != null } >= 5) AppCard {
                Text("Pace", fontWeight = FontWeight.SemiBold)
                val present = paces.filterNotNull()
                LineChart(
                    paces, invert = true,
                    topLabel = "fastest ${Fmt.pace(present.min().toDouble(), u)}", bottomLabel = "slowest ${Fmt.pace(present.max().toDouble(), u)}",
                    startLabel = "start", endLabel = Fmt.duration(r.durationS),
                )
            }
            val hrs = r.samples.map { it.hr?.toFloat() }
            if (hrs.count { it != null } >= 5) AppCard {
                Text("Heart rate", fontWeight = FontWeight.SemiBold)
                val present = hrs.filterNotNull()
                LineChart(
                    hrs, color = MaterialTheme.colorScheme.secondary,
                    topLabel = "${present.max().toInt()} bpm", bottomLabel = "${present.min().toInt()} bpm",
                    startLabel = "start", endLabel = Fmt.duration(r.durationS),
                )
            }
            val alts = r.samples.map { it.alt }
            if (alts.count { it != null } >= 5) AppCard {
                Text("Elevation", fontWeight = FontWeight.SemiBold)
                val present = alts.filterNotNull()
                LineChart(alts, color = MaterialTheme.colorScheme.tertiary, topLabel = "${present.max().toInt()} m", bottomLabel = "${present.min().toInt()} m")
            }
            val cads = r.samples.map { it.cad?.toFloat()?.takeIf { c -> c > 60 } }
            if (cads.count { it != null } >= 5) AppCard {
                Text("Cadence", fontWeight = FontWeight.SemiBold)
                val present = cads.filterNotNull()
                LineChart(cads, color = MaterialTheme.colorScheme.outline, fill = false, topLabel = "${present.max().toInt()} spm", bottomLabel = "${present.min().toInt()} spm")
            }

            if (r.stepResults.isNotEmpty()) AppCard {
                Text("Workout steps", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                r.stepResults.forEach { s ->
                    val pace = if (s.distanceM > 20) s.durationS / (s.distanceM / 1000.0) else null
                    val hit = s.target?.takeIf { it.hasPace }?.let { t -> pace != null && pace >= t.paceFast!! - 3 && pace <= t.paceSlow!! + 3 }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, fontWeight = FontWeight.Medium)
                            Fmt.target(s.target, u)?.let { Text("target $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${Fmt.pace(pace, u)} ${Fmt.paceUnit(u)}", fontWeight = FontWeight.SemiBold,
                                color = when (hit) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.secondary; null -> MaterialTheme.colorScheme.onSurface },
                            )
                            Text(
                                "${Fmt.distanceWithUnit(s.distanceM, u, 2)} · ${Fmt.duration(s.durationS)}" + (s.avgHr?.let { " · $it bpm" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val splits = remember(r.id, u) { if (r.samples.isNotEmpty()) Stats.splits(r.samples, u.metersPerUnit) else emptyList() }
            if (splits.isNotEmpty()) AppCard {
                Text("Splits", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val splitPaces = splits.map { it.durationS / (it.distanceM / 1000.0) }
                val fastest = splitPaces.min()
                splits.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        val full = s.distanceM >= u.metersPerUnit.toInt()
                        Text(if (full) "${s.index}" else Fmt.distance(s.distanceM, u, 1), Modifier.width(34.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).height(14.dp)) {
                            Box(
                                Modifier.fillMaxWidth((fastest / splitPaces[i]).toFloat().coerceIn(0.05f, 1f)).height(14.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                            )
                        }
                        Text(Fmt.pace(splitPaces[i], u), Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
                        Text(s.avgHr?.let { "$it" } ?: "", Modifier.width(40.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this run?") },
        text = { Text("It will be removed from your stats. This can't be undone.") },
        confirmButton = { TextButton(onClick = { repo.deleteRun(id); confirmDelete = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}
