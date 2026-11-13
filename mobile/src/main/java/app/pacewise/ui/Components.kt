package app.pacewise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pacewise.shared.Fmt
import app.pacewise.shared.Step
import app.pacewise.shared.StepKind
import app.pacewise.shared.Units
import app.pacewise.shared.Workout
import app.pacewise.shared.WorkoutType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
val LongDayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
val ShortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation) {
        Column(Modifier.padding(16.dp), content = content)
    } else Card(modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
fun TypeBadge(type: WorkoutType, modifier: Modifier = Modifier) {
    Text(
        type.title.uppercase(),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(type.color().copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = type.color(),
    )
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

fun workoutLength(w: Workout, u: Units): String = when {
    w.plannedDistanceM > 0 && w.type.isRun -> "${Fmt.distanceWithUnit(w.plannedDistanceM, u)} · about ${Fmt.durationWords(w.plannedDurationS)}"
    w.plannedDurationS > 0 -> Fmt.durationWords(w.plannedDurationS)
    else -> ""
}

fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DayFmt)
}

/** The steps of a workout as a compact timeline with targets. */
@Composable
fun StepList(steps: List<Step>, units: Units, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in steps) {
            val c = when (s.kind) {
                StepKind.WARMUP, StepKind.COOLDOWN -> MaterialTheme.colorScheme.outline
                StepKind.RECOVER -> MaterialTheme.colorScheme.tertiary
                StepKind.RUN -> MaterialTheme.colorScheme.primary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val sub = listOfNotNull(
                        Fmt.target(s.target, units),
                        s.runWalk?.let { "run ${Fmt.duration(it.runS)} / walk ${Fmt.duration(it.walkS)}" },
                    ).joinToString(" · ").ifEmpty { if (s.kind == StepKind.RECOVER) "easy jog or walk" else "by feel" }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.stepLength(s, units), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onMinus) { Icon(Icons.Default.Remove, "Less") }
            Text(
                value, modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            FilledTonalIconButton(onClick = onPlus) { Icon(Icons.Default.Add, "More") }
        }
    }
}
