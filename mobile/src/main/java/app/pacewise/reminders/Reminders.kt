package app.pacewise.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pacewise.MainActivity
import app.pacewise.PaceWiseApp
import app.pacewise.R
import app.pacewise.shared.Fmt
import app.pacewise.shared.RunRecord
import app.pacewise.shared.Stats
import app.pacewise.shared.Workout
import app.pacewise.shared.WorkoutStatus
import app.pacewise.shared.WorkoutType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ReminderKind { MORNING, EVENING, NUDGE, WEEKLY }

object Reminders {
    private const val CH_WORKOUT = "workouts"
    private const val CH_SYNC = "sync"
    private const val EXTRA_KIND = "kind"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, "Training reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Workout reminders, nudges and weekly summaries" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SYNC, "Watch sync", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Confirms when a run arrives from your watch" },
        )
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun pending(ctx: Context, kind: ReminderKind): PendingIntent = PendingIntent.getBroadcast(
        ctx, kind.ordinal,
        Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Cancels everything and schedules the next occurrence of each enabled reminder. */
    fun scheduleAll(ctx: Context) {
        val s = (ctx.applicationContext as PaceWiseApp).repo.reminders.value
        ReminderKind.entries.forEach { schedule(ctx, it, s) }
    }

    private fun schedule(ctx: Context, kind: ReminderKind, s: app.pacewise.shared.ReminderSettings) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, kind)
        am.cancel(pi)
        if (!s.enabled) return
        val now = LocalDateTime.now()
        fun nextDaily(h: Int, m: Int): LocalDateTime {
            val t = now.toLocalDate().atTime(LocalTime.of(h, m))
            return if (t.isAfter(now)) t else t.plusDays(1)
        }
        val at: LocalDateTime = when (kind) {
            ReminderKind.MORNING -> if (s.workoutDay) nextDaily(s.hour, s.minute) else return
            ReminderKind.EVENING -> if (s.eveningBefore) nextDaily(s.eveningHour, s.eveningMinute) else return
            ReminderKind.NUDGE -> if (s.missedNudge) nextDaily(s.nudgeHour, s.nudgeMinute) else return
            ReminderKind.WEEKLY -> if (s.weeklySummary) {
                val t = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(18, 0)
                if (t.isAfter(now)) t else t.plusWeeks(1)
            } else return
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Inexact-while-idle: arrives within a few minutes, needs no special permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }

    private fun km(ctx: Context, w: Workout): String {
        val u = (ctx.applicationContext as PaceWiseApp).repo.profile.value?.units ?: return ""
        return if (w.plannedDistanceM > 0) " · ${Fmt.distanceWithUnit(w.plannedDistanceM, u)}" else ""
    }

    fun fire(ctx: Context, kind: ReminderKind) {
        val repo = (ctx.applicationContext as PaceWiseApp).repo
        val s = repo.reminders.value
        schedule(ctx, kind, s)
        val plan = repo.plan.value ?: return
        val profile = repo.profile.value ?: return
        val today = LocalDate.now()
        val runs = repo.runs.value
        fun open(w: Workout) = Stats.status(w, plan, runs, repo.completions.value, today).let {
            it == WorkoutStatus.TODAY || it == WorkoutStatus.UPCOMING
        }
        when (kind) {
            ReminderKind.MORNING -> plan.workoutOn(today)?.takeIf(::open)?.let { w ->
                show(ctx, 1, "Today: ${w.title}${km(ctx, w)}", w.description)
            }
            ReminderKind.EVENING -> plan.workoutOn(today.plusDays(1))?.takeIf(::open)?.let { w ->
                val big = w.type == WorkoutType.LONG || w.type == WorkoutType.RACE
                if (big || !s.eveningLongRunOnly) show(
                    ctx, 2, "Tomorrow: ${w.title}${km(ctx, w)}",
                    if (big) "Lay out your kit, plan your route and fuel, and charge your watch tonight."
                    else "Get your kit ready so tomorrow is easy to start.",
                )
            }
            ReminderKind.NUDGE -> plan.workoutOn(today)?.takeIf { it.type.isRun && open(it) }?.let { w ->
                show(ctx, 3, "Still time for today's ${w.title.lowercase()}", "Even a shortened version keeps your plan on track. Or mark it skipped and move on.")
            }
            ReminderKind.WEEKLY -> {
                val week = Stats.weekly(runs, plan, today, 1).last()
                val next = plan.weekOf(today.plusDays(7))
                val done = "This week: ${Fmt.distanceWithUnit(week.distanceM, profile.units)} in ${week.runs} run${if (week.runs == 1) "" else "s"}"
                val ahead = next?.let { n ->
                    val long = n.workouts.firstOrNull { it.type == WorkoutType.LONG || it.type == WorkoutType.RACE }
                    "Next week (${n.phase.title}): ${n.workouts.size} sessions, ${Fmt.distanceWithUnit(n.plannedDistanceM, profile.units, 0)}" +
                        (long?.let { ". ${it.title}: ${Fmt.distanceWithUnit(it.plannedDistanceM, profile.units, 0)}" } ?: "")
                } ?: "Open PaceWise to see what's next."
                show(ctx, 4, done, ahead)
            }
        }
    }

    fun notifyRunSynced(ctx: Context, run: RunRecord) {
        val u = (ctx.applicationContext as PaceWiseApp).repo.profile.value?.units ?: app.pacewise.shared.Units.KM
        show(
            ctx, 100, "Run synced from your watch",
            "${run.title}: ${Fmt.distanceWithUnit(run.distanceM, u, 2)} in ${Fmt.duration(run.durationS)} · ${Fmt.pace(run.avgPace, u)} ${Fmt.paceUnit(u)}",
            CH_SYNC,
        )
    }

    private fun show(ctx: Context, id: Int, title: String, text: String, channel: String = CH_WORKOUT) {
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_run)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    internal fun kindOf(intent: Intent): ReminderKind? =
        intent.getStringExtra(EXTRA_KIND)?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.kindOf(intent)?.let { Reminders.fire(context, it) }
    }
}

/** Alarms are lost on reboot and shift with clock changes, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.scheduleAll(context)
    }
}
