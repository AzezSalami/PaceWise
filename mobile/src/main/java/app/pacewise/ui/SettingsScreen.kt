package app.pacewise.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pacewise.data.Repository
import app.pacewise.reminders.Reminders
import app.pacewise.shared.Fmt
import app.pacewise.shared.ReminderSettings
import app.pacewise.shared.Units
import app.pacewise.shared.WatchSettings
import app.pacewise.sync.PhoneSync
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repo: Repository, onBack: () -> Unit, onEditPlan: () -> Unit) {
    val profile = repo.profile.collectAsStateWithLifecycle().value ?: return
    val rem by repo.reminders.collectAsStateWithLifecycle()
    val watch by repo.watchSettings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var canNotify by remember { mutableStateOf(Reminders.canNotify(ctx)) }
    var watchName by remember { mutableStateOf<String?>(null) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { watchName = PhoneSync.connectedWatch(ctx) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = Reminders.canNotify(ctx) }

    fun saveRem(r: ReminderSettings) {
        repo.saveReminders(r)
        Reminders.scheduleAll(ctx)
        if (r.enabled && !Reminders.canNotify(ctx) && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun saveWatch(w: WatchSettings) { repo.saveWatchSettings(w); PhoneSync.pushPlan(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- plan
            SectionTitle("Training plan")
            AppCard {
                Text(profile.method.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(profile.method.tagline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("${profile.raceName} · ${LocalDate.parse(profile.raceDate).format(DayFmt)} · goal ${Fmt.duration(profile.goalTimeS)}", style = MaterialTheme.typography.bodySmall)
                Text("${profile.effectiveDays} runs a week · ${profile.level.title}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onEditPlan) { Text("Change method, goal or schedule") }
                Text("Your recorded runs are kept when the plan is rebuilt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            // --- reminders
            SectionTitle("Reminders")
            AppCard {
                SwitchRow("Reminders", "Turn all training notifications on or off", rem.enabled) { saveRem(rem.copy(enabled = it)) }
                if (rem.enabled && !canNotify) {
                    Text("Notifications are blocked for PaceWise, so reminders can't be shown.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                }
                if (rem.enabled) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Workout day", "What's planned today, with distance and pacing", rem.workoutDay) { saveRem(rem.copy(workoutDay = it)) }
                    if (rem.workoutDay) TimeRow("Remind me at", rem.hour, rem.minute) { h, m -> saveRem(rem.copy(hour = h, minute = m)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Evening before", "A heads-up to prepare kit, route and watch", rem.eveningBefore) { saveRem(rem.copy(eveningBefore = it)) }
                    if (rem.eveningBefore) {
                        TimeRow("Remind me at", rem.eveningHour, rem.eveningMinute) { h, m -> saveRem(rem.copy(eveningHour = h, eveningMinute = m)) }
                        SwitchRow("Only before long runs and race day", null, rem.eveningLongRunOnly) { saveRem(rem.copy(eveningLongRunOnly = it)) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Nudge if I haven't run", "A gentle push when today's session is still open", rem.missedNudge) { saveRem(rem.copy(missedNudge = it)) }
                    if (rem.missedNudge) TimeRow("Nudge me at", rem.nudgeHour, rem.nudgeMinute) { h, m -> saveRem(rem.copy(nudgeHour = h, nudgeMinute = m)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Weekly summary", "Sunday evening: your week and what's next", rem.weeklySummary) { saveRem(rem.copy(weeklySummary = it)) }
                }
            }

            // --- watch
            SectionTitle("Watch")
            AppCard {
                Text(watchName?.let { "$it connected" } ?: "No watch connected right now", fontWeight = FontWeight.Medium)
                Text(
                    "Install PaceWise on your Wear OS watch (Galaxy Watch4 or newer). Workouts go to the watch and finished runs come back by themselves.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        syncMsg = "Syncing…"
                        syncMsg = if (PhoneSync.requestSync(ctx)) "Sync requested. New runs arrive within a few seconds." else "Couldn't reach a watch. Check Bluetooth and try again."
                        watchName = PhoneSync.connectedWatch(ctx)
                    }
                }) { Text("Sync now") }
                syncMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow("Vibrate for pace cues", "Two short buzzes: speed up. One long buzz: slow down.", watch.vibrateCues) { saveWatch(watch.copy(vibrateCues = it)) }
                if (watch.vibrateCues) {
                    Text("Repeat a cue at most every", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 20, 30, 60).forEach { s ->
                            FilterChip(watch.cueIntervalS == s, { saveWatch(watch.copy(cueIntervalS = s)) }, label = { Text("$s s") })
                        }
                    }
                }
                SwitchRow("Auto-pause", "Pause the clock when you stop, e.g. at traffic lights", watch.autoPause) { saveWatch(watch.copy(autoPause = it)) }
                SwitchRow("Keep screen on while running", "Easier to glance at, uses more battery", watch.keepScreenOn) { saveWatch(watch.copy(keepScreenOn = it)) }
            }

            // --- units
            SectionTitle("Units")
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(profile.units == Units.KM, { repo.saveProfile(profile.copy(units = Units.KM), regenerate = false); PhoneSync.pushPlan(ctx) }, label = { Text("Kilometres") })
                    FilterChip(profile.units == Units.MI, { repo.saveProfile(profile.copy(units = Units.MI), regenerate = false); PhoneSync.pushPlan(ctx) }, label = { Text("Miles") })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        val state = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onSet(state.hour, state.minute); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}
