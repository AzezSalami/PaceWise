package app.pacewise.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pacewise.shared.WorkoutType

private val Light = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3FAE8),
    onPrimaryContainer = Color(0xFF03281D),
    secondary = Color(0xFFE8590C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8CC),
    onSecondaryContainer = Color(0xFF3D1A00),
    tertiary = Color(0xFF1971C2),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF17201D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201D),
    surfaceVariant = Color(0xFFE6ECE9),
    onSurfaceVariant = Color(0xFF52605B),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F4F2),
    surfaceContainerHighest = Color(0xFFE6ECE9),
    surfaceContainerLow = Color(0xFFFFFFFF),
    outline = Color(0xFF8A9A94),
    outlineVariant = Color(0xFFD5DDDA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF38D9A9),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B5C45),
    onPrimaryContainer = Color(0xFFC3FAE8),
    secondary = Color(0xFFFFA94D),
    onSecondary = Color(0xFF4A2300),
    secondaryContainer = Color(0xFF6B3A0A),
    onSecondaryContainer = Color(0xFFFFE8CC),
    tertiary = Color(0xFF74C0FC),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE3EAE7),
    surface = Color(0xFF161F1C),
    onSurface = Color(0xFFE3EAE7),
    surfaceVariant = Color(0xFF26322E),
    onSurfaceVariant = Color(0xFFA9B8B2),
    surfaceContainer = Color(0xFF161F1C),
    surfaceContainerHigh = Color(0xFF1E2925),
    surfaceContainerHighest = Color(0xFF26322E),
    surfaceContainerLow = Color(0xFF161F1C),
    outline = Color(0xFF74847E),
    outlineVariant = Color(0xFF33413C),
)

@Composable
fun PaceWiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}

/** One colour per kind of session, readable on light and dark surfaces. */
fun WorkoutType.color(): Color = when (this) {
    WorkoutType.EASY, WorkoutType.FREE -> Color(0xFF20A779)
    WorkoutType.RECOVERY -> Color(0xFF5FA8A0)
    WorkoutType.LONG, WorkoutType.MEDIUM_LONG -> Color(0xFF3B82D6)
    WorkoutType.TEMPO -> Color(0xFFF08C00)
    WorkoutType.MARATHON_PACE -> Color(0xFF9B5DE5)
    WorkoutType.INTERVALS, WorkoutType.HILLS -> Color(0xFFE5484D)
    WorkoutType.TEST -> Color(0xFFD6457F)
    WorkoutType.RACE -> Color(0xFFE0A100)
    WorkoutType.CROSS_TRAIN -> Color(0xFF8A9A94)
}
