package app.pacewise.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import app.pacewise.shared.Workout

class WearActivity : ComponentActivity() {
    private fun missingPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Wear OS 6 replaced BODY_SENSORS with a dedicated heart-rate permission.
        add(if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearSync.fetchPlan(this)
        WearSync.uploadPending(this)

        setContent {
            MaterialTheme(colors = WearColors) {
                val ui by RunSession.ui.collectAsStateWithLifecycle()
                val payload by store.payload.collectAsStateWithLifecycle()
                val pending by store.pendingCount.collectAsStateWithLifecycle()
                var preview by remember { mutableStateOf<Workout?>(null) }
                var queued by remember { mutableStateOf<Pair<Boolean, Workout?>>(false to null) }

                val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    // Movement tracking is the minimum; without it nothing can be recorded.
                    val canRecord = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                    if (queued.first && canRecord) ExerciseService.send(this, ExerciseService.ACTION_START, queued.second)
                    queued = false to null
                }

                fun start(w: Workout?) {
                    preview = null
                    val missing = missingPermissions()
                    if (missing.isEmpty()) ExerciseService.send(this, ExerciseService.ACTION_START, w)
                    else { queued = true to w; askPermissions.launch(missing) }
                }

                val keepOn = ui.running && payload.settings.keepScreenOn
                DisposableEffect(keepOn) {
                    if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                when {
                    ui.running -> {
                        BackHandler {} // the back button must never end a run by accident
                        RunScreen(ui, payload)
                    }
                    ui.phase == RunPhase.FINISHED && ui.summary != null -> {
                        BackHandler { RunSession.clearSummary() }
                        SummaryScreen(ui.summary!!, payload.units, pending) { RunSession.clearSummary() }
                    }
                    preview != null -> {
                        BackHandler { preview = null }
                        PreviewScreen(preview!!, payload.units) { start(preview) }
                    }
                    else -> HomeScreen(payload, pending, ui.message, onPreview = { preview = it }, onFreeRun = { start(null) })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WearSync.uploadPending(this)
    }
}
