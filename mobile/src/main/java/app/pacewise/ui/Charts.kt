package app.pacewise.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pacewise.shared.Sample
import kotlin.math.cos
import kotlin.math.max

/**
 * Bars for what was run each week, with a dashed marker showing what the plan asked for.
 */
@Composable
fun WeeklyBars(
    actual: List<Float>, planned: List<Float>, labels: List<String>, modifier: Modifier = Modifier,
    highlightLast: Boolean = true,
) {
    val bar = MaterialTheme.colorScheme.primary
    val barDim = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val top = max(max(actual.maxOrNull() ?: 0f, planned.maxOrNull() ?: 0f), 1f) * 1.1f
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val n = actual.size
            val slot = size.width / n
            val w = slot * 0.62f
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            for (i in 0 until n) {
                val x = slot * i + (slot - w) / 2
                val h = size.height * actual[i] / top
                if (h > 0) drawRoundRect(
                    if (highlightLast && i == n - 1) bar else barDim,
                    Offset(x, size.height - h), Size(w, h), CornerRadius(4.dp.toPx()),
                )
                if (planned[i] > 0) {
                    val y = size.height - size.height * planned[i] / top
                    drawLine(
                        mark, Offset(x - 2.dp.toPx(), y), Offset(x + w + 2.dp.toPx(), y), 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * A simple line chart. Null values break the line. Set [invert] for pace, where smaller
 * numbers should sit higher.
 */
@Composable
fun LineChart(
    values: List<Float?>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    invert: Boolean = false, fill: Boolean = true, band: Pair<Float, Float>? = null,
    topLabel: String? = null, bottomLabel: String? = null, startLabel: String? = null, endLabel: String? = null,
) {
    val present = values.filterNotNull()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val bandColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
    Column(modifier) {
        if (topLabel != null) Text(topLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp)) {
            if (present.size < 2) return@Canvas
            var lo = present.min()
            var hi = present.max()
            band?.let { lo = minOf(lo, it.first); hi = maxOf(hi, it.second) }
            if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            fun y(v: Float): Float {
                val f = (v - lo) / (hi - lo)
                return if (invert) size.height * f else size.height * (1 - f)
            }
            fun x(i: Int) = size.width * i / (values.size - 1).coerceAtLeast(1)
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            band?.let {
                val y1 = y(it.first); val y2 = y(it.second)
                drawRect(bandColor, Offset(0f, minOf(y1, y2)), Size(size.width, kotlin.math.abs(y2 - y1)))
            }
            val path = Path()
            val area = Path()
            var started = false
            var lastX = 0f
            var firstX = 0f
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val px = x(i); val py = y(v)
                if (!started) { path.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py); started = true; firstX = px }
                else { path.lineTo(px, py); area.lineTo(px, py) }
                lastX = px
            }
            if (fill) {
                area.lineTo(lastX, size.height); area.lineTo(firstX, size.height); area.close()
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (bottomLabel != null) Text(bottomLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (startLabel != null || endLabel != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The GPS track drawn to scale (no map tiles, works offline). */
@Composable
fun RouteMap(samples: List<Sample>, modifier: Modifier = Modifier) {
    val pts = samples.filter { it.lat != null && it.lon != null }
    val line = MaterialTheme.colorScheme.primary
    val start = Color(0xFF20A779)
    val end = MaterialTheme.colorScheme.secondary
    Canvas(modifier.fillMaxWidth().height(200.dp).padding(12.dp)) {
        if (pts.size < 2) return@Canvas
        val lat0 = pts.minOf { it.lat!! }; val lat1 = pts.maxOf { it.lat!! }
        val lon0 = pts.minOf { it.lon!! }; val lon1 = pts.maxOf { it.lon!! }
        val kx = cos(Math.toRadians((lat0 + lat1) / 2))
        val w = max((lon1 - lon0) * kx, 1e-6)
        val h = max(lat1 - lat0, 1e-6)
        val scale = minOf(size.width / w, size.height / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        fun p(s: Sample) = Offset((ox + (s.lon!! - lon0) * kx * scale).toFloat(), (size.height - oy - (s.lat!! - lat0) * scale).toFloat())
        val path = Path()
        pts.forEachIndexed { i, s -> val o = p(s); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, line, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(start, 6.dp.toPx(), p(pts.first()))
        drawCircle(end, 6.dp.toPx(), p(pts.last()))
    }
}
