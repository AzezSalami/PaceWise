package app.pacewise.shared

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Training paces (s/km) derived from a fitness score. */
@Serializable
data class Paces(
    val vdot: Double,
    val recovery: PaceRange,
    val easy: PaceRange,
    val long: PaceRange,
    val marathon: PaceRange,
    val threshold: PaceRange,
    val interval: PaceRange,
    val repetition: PaceRange,
)

@Serializable
data class HrZones(val maxHr: Int, val easyCap: Int, val aerobicCeiling: Int, val thresholdLow: Int, val thresholdHigh: Int) {
    /** Zone 1–5 for a heart rate. */
    fun zone(hr: Int): Int {
        val p = hr.toDouble() / maxHr
        return when {
            p < 0.6 -> 1
            p < 0.72 -> 2
            p < 0.82 -> 3
            p < 0.9 -> 4
            else -> 5
        }
    }
}

/**
 * Pace maths using the Daniels–Gilbert oxygen cost equations, the model behind VDOT tables.
 */
object PaceCalc {
    /** Oxygen cost (ml/kg/min) of running at [v] metres per minute. */
    private fun vo2(v: Double) = -4.60 + 0.182258 * v + 0.000104 * v * v

    /** Fraction of VO2max that can be sustained for [tMin] minutes. */
    private fun fraction(tMin: Double) =
        0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)

    private fun velocity(vo2: Double): Double {
        val a = 0.000104
        val b = 0.182258
        val c = -4.60 - vo2
        return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
    }

    fun vdot(distanceM: Double, timeS: Double): Double {
        val t = timeS / 60.0
        return vo2(distanceM / t) / fraction(t)
    }

    /** Predicted race time in seconds for [distanceM] at a given fitness. */
    fun predictTime(vdot: Double, distanceM: Double = MARATHON_M.toDouble()): Int {
        var lo = distanceM / 8.0   // 8 m/s, faster than anyone
        var hi = distanceM / 1.2   // slow walk
        repeat(50) {
            val mid = (lo + hi) / 2
            if (vdot(distanceM, mid) > vdot) lo = mid else hi = mid
        }
        return ((lo + hi) / 2).roundToInt()
    }

    private fun paceAt(vdot: Double, pct: Double): Int = (60000.0 / velocity(vdot * pct)).roundToInt()

    fun pacesForMarathon(goalTimeS: Int): Paces = paces(vdot(MARATHON_M.toDouble(), goalTimeS.toDouble()), goalTimeS)

    fun paces(vdot: Double, marathonTimeS: Int = predictTime(vdot)): Paces {
        val m = (marathonTimeS / (MARATHON_M / 1000.0)).roundToInt()
        val easyFast = maxOf(paceAt(vdot, 0.74), m + 20)
        val easySlow = maxOf(paceAt(vdot, 0.62), easyFast + 35)
        val t = minOf(paceAt(vdot, 0.88), m - 8)
        val i = minOf(paceAt(vdot, 0.975), t - 12)
        val r = minOf(paceAt(vdot, 1.07), i - 10)
        return Paces(
            vdot = (vdot * 10).roundToInt() / 10.0,
            recovery = PaceRange(easySlow, easySlow + 45),
            easy = PaceRange(easyFast, easySlow),
            long = PaceRange(easyFast + 5, easySlow + 10),
            marathon = PaceRange(m - 5, m + 5),
            threshold = PaceRange(t - 4, t + 4),
            interval = PaceRange(i - 4, i + 4),
            repetition = PaceRange(r - 5, r + 5),
        )
    }

    fun hrZones(age: Int, measuredMax: Int?): HrZones {
        val max = measuredMax ?: (208 - 0.7 * age).roundToInt()
        return HrZones(
            maxHr = max,
            easyCap = (max * 0.78).roundToInt(),
            aerobicCeiling = 180 - age,
            thresholdLow = (max * 0.84).roundToInt(),
            thresholdHigh = (max * 0.9).roundToInt(),
        )
    }
}

fun PaceRange.toTarget(hrMax: Int? = null) = Target(paceFast = fast, paceSlow = slow, hrMax = hrMax)
fun PaceRange.shift(deltaS: Int) = PaceRange(fast + deltaS, slow + deltaS)

object Fmt {
    fun paceUnit(u: Units) = "/${u.label}"

    /** "5:12" for a pace given in seconds per km, converted to the runner's unit. */
    fun pace(secPerKm: Double?, u: Units): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm > 3600) return "--:--"
        val s = (secPerKm * u.metersPerUnit / 1000.0).roundToInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun pace(secPerKm: Int, u: Units) = pace(secPerKm.toDouble(), u)

    fun paceRange(fast: Int, slow: Int, u: Units) = "${pace(fast, u)}–${pace(slow, u)}"

    fun target(t: Target?, u: Units): String? = when {
        t == null -> null
        t.hasPace -> paceRange(t.paceFast!!, t.paceSlow!!, u) + " " + paceUnit(u)
        t.hrMin != null && t.hrMax != null -> "${t.hrMin}–${t.hrMax} bpm"
        t.hrMax != null -> "under ${t.hrMax} bpm"
        else -> null
    }

    fun distance(m: Number, u: Units, decimals: Int = 1): String = "%.${decimals}f".format(m.toDouble() / u.metersPerUnit)

    fun distanceWithUnit(m: Number, u: Units, decimals: Int = 1) = "${distance(m, u, decimals)} ${u.label}"

    /** "1:02:33" or "42:10". */
    fun duration(totalS: Number): String {
        val s = totalS.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "3h 45m" */
    fun durationWords(totalS: Number): String {
        val s = totalS.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${"%02d".format(m)}m" else "${m}m"
    }

    fun stepLength(step: Step, u: Units): String = when {
        step.distanceM != null -> {
            val d = step.distanceM
            if (u == Units.KM && d < 1000) "$d m" else distanceWithUnit(d, u, if (d % 1000 == 0 && u == Units.KM) 0 else 1)
        }
        step.durationS != null -> duration(step.durationS)
        else -> ""
    }
}
