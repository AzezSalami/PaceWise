package app.pacewise.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pacewise.data.Repository
import app.pacewise.reminders.Reminders
import app.pacewise.shared.Backup
import app.pacewise.sync.PhoneSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val MAX_BACKUP_BYTES = 64L * 1024 * 1024

private suspend fun export(ctx: Context, repo: Repository, uri: Uri): String = withContext(Dispatchers.IO) {
    try {
        val bytes = repo.exportBackup()
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext "Couldn't write to that location."
        val n = repo.runs.value.size
        "Backup saved with your plan, settings and $n run${if (n == 1) "" else "s"}."
    } catch (e: Exception) {
        "Export failed. Try saving to a different folder."
    }
}

private suspend fun import(ctx: Context, repo: Repository, uri: Uri): String = withContext(Dispatchers.IO) {
    val bytes = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BACKUP_BYTES) return@withContext "That file is too large to be a PaceWise backup."
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    } ?: return@withContext "Couldn't open that file."
    try {
        val b = repo.importBackup(bytes)
        Reminders.scheduleAll(ctx)
        PhoneSync.pushPlan(ctx)
        "Restored ${if (b.profile != null) "your plan, settings and " else ""}${b.runs.size} run${if (b.runs.size == 1) "" else "s"}."
    } catch (e: Exception) {
        "That file isn't a PaceWise backup. Nothing was changed."
    }
}

/** Export and import buttons shown in Settings. */
@Composable
fun BackupCard(repo: Repository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch { message = export(ctx, repo, uri) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }

    AppCard {
        Text("Back up and restore", fontWeight = FontWeight.Medium)
        Text(
            "Export saves your plan, progress, every run and your settings to one file. Send it to your new phone " +
                "(Drive, email, cable), install PaceWise there and choose Import.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save.launch("pacewise-backup-${LocalDate.now()}.${Backup.FILE_EXTENSION}") }, modifier = Modifier.weight(1f)) { Text("Export") }
            OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import this backup?") },
            text = { Text("Your current plan, goal and settings on this phone will be replaced by the ones in the backup. Runs already on this phone are kept and the backup's runs are added.") },
            confirmButton = { TextButton(onClick = { pending = null; scope.launch { message = import(ctx, repo, uri) } }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

/** Shown on the first setup screen so a new phone can skip the wizard entirely. */
@Composable
fun RestoreOnSetup(repo: Repository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = import(ctx, repo, uri) }
    }
    AppCard {
        Text("Coming from another phone?", fontWeight = FontWeight.SemiBold)
        Text(
            "Restore a PaceWise backup to bring back your plan, runs and settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Restore from backup") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
    }
    Spacer(Modifier.height(16.dp))
}
