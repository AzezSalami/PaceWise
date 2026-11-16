package app.pacewise.data

import android.content.Context
import app.pacewise.shared.Backup
import app.pacewise.shared.Completion
import app.pacewise.shared.PaceCalc
import app.pacewise.shared.PlanGenerator
import app.pacewise.shared.Profile
import app.pacewise.shared.ReminderSettings
import app.pacewise.shared.RunRecord
import app.pacewise.shared.Stats
import app.pacewise.shared.SyncProtocol
import app.pacewise.shared.TrainingPlan
import app.pacewise.shared.WatchPayload
import app.pacewise.shared.WatchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate

/**
 * Single source of truth for the phone app. Everything is kept as small JSON files in the
 * app's private storage and exposed as StateFlows for the UI.
 */
class Repository(context: Context) {
    private val dir: File = context.filesDir
    private val runDir = File(dir, "runs").apply { mkdirs() }
    private val json = SyncProtocol.json

    private val completionSer = MapSerializer(String.serializer(), Completion.serializer())
    private val runListSer = ListSerializer(RunRecord.serializer())

    private val _profile = MutableStateFlow(read("profile.json", Profile.serializer()))
    private val _plan = MutableStateFlow(read("plan.json", TrainingPlan.serializer()))
    private val _completions = MutableStateFlow(read("completions.json", completionSer) ?: emptyMap())
    private val _runs = MutableStateFlow(read("runs.json", runListSer) ?: emptyList())
    private val _reminders = MutableStateFlow(read("reminders.json", ReminderSettings.serializer()) ?: ReminderSettings())
    private val _watch = MutableStateFlow(read("watch.json", WatchSettings.serializer()) ?: WatchSettings())

    val profile: StateFlow<Profile?> = _profile
    val plan: StateFlow<TrainingPlan?> = _plan
    val completions: StateFlow<Map<String, Completion>> = _completions

    /** Run summaries (no samples), newest first. */
    val runs: StateFlow<List<RunRecord>> = _runs
    val reminders: StateFlow<ReminderSettings> = _reminders
    val watchSettings: StateFlow<WatchSettings> = _watch

    private fun <T> read(name: String, ser: KSerializer<T>): T? = try {
        File(dir, name).takeIf { it.exists() }?.let { json.decodeFromString(ser, it.readText()) }
    } catch (e: Exception) {
        null
    }

    private fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(ser, value))
        tmp.renameTo(File(dir, name))
    }

    /** Saves the profile and rebuilds the plan from it. */
    @Synchronized
    fun saveProfile(p: Profile, regenerate: Boolean = true) {
        write("profile.json", Profile.serializer(), p)
        _profile.value = p
        if (regenerate) {
            val plan = PlanGenerator.generate(p)
            write("plan.json", TrainingPlan.serializer(), plan)
            _plan.value = plan
            val ids = plan.allWorkouts().map { it.id }.toSet()
            saveCompletions(_completions.value.filterKeys { it in ids })
        }
    }

    private fun saveCompletions(map: Map<String, Completion>) {
        write("completions.json", completionSer, map)
        _completions.value = map
    }

    @Synchronized
    fun setCompletion(workoutId: String, c: Completion?) {
        saveCompletions(if (c == null) _completions.value - workoutId else _completions.value + (workoutId to c))
    }

    /** Stores a run (replacing one with the same id). Returns false if it was already there. */
    @Synchronized
    fun saveRun(run: RunRecord): Boolean {
        val existed = _runs.value.any { it.id == run.id }
        File(runDir, "${run.id}.json").writeText(json.encodeToString(RunRecord.serializer(), run))
        val list = (_runs.value.filter { it.id != run.id } + run.summary()).sortedByDescending { it.startEpochMs }
        write("runs.json", runListSer, list)
        _runs.value = list
        return !existed
    }

    /** Full run including samples. */
    fun loadRun(id: String): RunRecord? = try {
        File(runDir, "$id.json").takeIf { it.exists() }?.let { json.decodeFromString(RunRecord.serializer(), it.readText()) }
    } catch (e: Exception) {
        _runs.value.firstOrNull { it.id == id }
    }

    @Synchronized
    fun deleteRun(id: String) {
        File(runDir, "$id.json").delete()
        val list = _runs.value.filter { it.id != id }
        write("runs.json", runListSer, list)
        _runs.value = list
    }

    fun saveReminders(r: ReminderSettings) {
        write("reminders.json", ReminderSettings.serializer(), r)
        _reminders.value = r
    }

    fun saveWatchSettings(w: WatchSettings) {
        write("watch.json", WatchSettings.serializer(), w)
        _watch.value = w
    }

    /** Everything in one file, for moving to another phone. */
    @Synchronized
    fun exportBackup(): ByteArray = Backup(
        exportedEpochMs = System.currentTimeMillis(),
        profile = _profile.value, plan = _plan.value, completions = _completions.value,
        runs = _runs.value.map { loadRun(it.id) ?: it },
        reminders = _reminders.value, watchSettings = _watch.value,
    ).encode()

    /**
     * Restores a backup. Profile, plan and settings are replaced; runs are merged, so runs
     * already on this phone are kept. Returns the backup that was applied, or throws if the
     * file is not a PaceWise backup.
     */
    @Synchronized
    fun importBackup(bytes: ByteArray): Backup {
        val b = Backup.decode(bytes)
        b.profile?.let { p ->
            write("profile.json", Profile.serializer(), p)
            _profile.value = p
            // Use the saved plan so progress lines up exactly; rebuild only if it is missing.
            val plan = b.plan ?: PlanGenerator.generate(p)
            write("plan.json", TrainingPlan.serializer(), plan)
            _plan.value = plan
            saveCompletions(b.completions)
        }
        b.runs.forEach { saveRun(it) }
        saveReminders(b.reminders)
        saveWatchSettings(b.watchSettings)
        return b
    }

    /** What gets pushed to the watch: the next four weeks of runs plus coaching settings. */
    fun watchPayload(today: LocalDate = LocalDate.now()): WatchPayload? {
        val p = _profile.value ?: return null
        val plan = _plan.value ?: return null
        val from = today.minusDays(1)
        val to = today.plusDays(28)
        val upcoming = plan.allWorkouts().filter { it.type.isRun && !it.localDate.isBefore(from) && !it.localDate.isAfter(to) }
        val runs = _runs.value
        val done = upcoming.filter { _completions.value[it.id] != null || Stats.runFor(it, runs) != null }.map { it.id }
        return WatchPayload(
            units = p.units, methodTitle = p.method.title, raceDate = p.raceDate,
            hrZones = PaceCalc.hrZones(p.age, p.maxHr), settings = _watch.value,
            workouts = upcoming, doneIds = done, updatedEpochMs = System.currentTimeMillis(),
        )
    }
}
