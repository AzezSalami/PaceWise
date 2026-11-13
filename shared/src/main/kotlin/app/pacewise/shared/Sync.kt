package app.pacewise.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Everything the watch needs to coach without the phone nearby. */
@Serializable
data class WatchPayload(
    val units: Units = Units.KM,
    val methodTitle: String = "",
    val raceDate: String? = null,
    val hrZones: HrZones? = null,
    val settings: WatchSettings = WatchSettings(),
    /** The next few weeks of runnable workouts. */
    val workouts: List<Workout> = emptyList(),
    /** Ids already completed, so the watch doesn't offer them again. */
    val doneIds: List<String> = emptyList(),
    val updatedEpochMs: Long = 0,
)

/** Wearable Data Layer contract shared by both apps. */
object SyncProtocol {
    /** Phone → watch DataItem holding a [WatchPayload]. */
    const val PATH_PLAN = "/pacewise/plan"

    /** Watch → phone DataItems, one per finished run: "/pacewise/run/<id>". */
    const val PATH_RUN_PREFIX = "/pacewise/run/"

    /** Phone → watch message: the run with this id (payload) is stored safely. */
    const val PATH_RUN_ACK = "/pacewise/run-ack"

    /** Phone → watch message asking the watch to re-send anything unsynced. */
    const val PATH_REQUEST_SYNC = "/pacewise/request-sync"

    const val KEY_JSON = "json"
    const val KEY_RUN = "run"
    const val KEY_TIME = "time"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }

    fun encodeRun(run: RunRecord): ByteArray = gzip(json.encodeToString(RunRecord.serializer(), run))
    fun decodeRun(bytes: ByteArray): RunRecord = json.decodeFromString(RunRecord.serializer(), gunzip(bytes))
}
