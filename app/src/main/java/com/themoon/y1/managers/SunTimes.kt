package com.themoon.y1.managers

import java.util.Calendar
import java.util.TimeZone

/**
 * Offline sunrise and sunset (NOAA solar calculator equations, accurate to about one minute
 * between latitudes ±72°), used by the Auto theme to tell day from night.
 */
object SunTimes {

    sealed class Result {
        /** Sunrise and sunset as epoch milliseconds. */
        data class Times(val sunriseMs: Long, val sunsetMs: Long) : Result()
        /** The sun does not set on this date (midnight sun). */
        object PolarDay : Result()
        /** The sun does not rise on this date. */
        object PolarNight : Result()
    }

    // Apparent sunrise: upper limb on the horizon, with standard refraction
    private const val ZENITH_DEG = 90.833

    // Used when the time zone is not in [ZoneCoordinates]: day from 07:00 to 19:00 local time
    const val FALLBACK_DAY_START_HOUR = 7
    const val FALLBACK_DAY_END_HOUR = 19

    /**
     * Sunrise and sunset on the calendar date [year]-[month]-[day] (month 1..12) at
     * [latitude] (north positive) and [longitude] (east positive). The date is the local date:
     * the times are near local noon of that date, so they can fall on the UTC date before or after.
     */
    fun compute(year: Int, month: Int, day: Int, latitude: Double, longitude: Double): Result {
        val utcMidnightMs = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
        // Julian day at 0h UTC of the date
        val jd0 = utcMidnightMs / 86_400_000.0 + 2440587.5

        val noon = solarNoonMinutes(jd0, longitude)
        val noonCosHa = cosHourAngle(jd0 + noon / 1440.0, latitude)
        if (noonCosHa > 1.0) return Result.PolarNight
        if (noonCosHa < -1.0) return Result.PolarDay

        val sunrise = eventMinutes(jd0, latitude, longitude, rising = true)
        val sunset = eventMinutes(jd0, latitude, longitude, rising = false)
        return Result.Times(
            utcMidnightMs + Math.round(sunrise * 60_000.0),
            utcMidnightMs + Math.round(sunset * 60_000.0)
        )
    }

    /** True when it is day at [nowMs] in the time zone [zoneId], at the zone's city. */
    fun isDaytime(zoneId: String, nowMs: Long): Boolean {
        val zone = TimeZone.getTimeZone(zoneId)
        val local = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val coords = ZoneCoordinates.forZone(zoneId)
        if (coords == null) {
            val hour = local.get(Calendar.HOUR_OF_DAY)
            return hour in FALLBACK_DAY_START_HOUR until FALLBACK_DAY_END_HOUR
        }
        val result = compute(
            local.get(Calendar.YEAR), local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH),
            coords.latitude, coords.longitude
        )
        return when (result) {
            is Result.PolarDay -> true
            is Result.PolarNight -> false
            is Result.Times -> nowMs >= result.sunriseMs && nowMs < result.sunsetMs
        }
    }

    /**
     * The first sunrise or sunset after [nowMs] in the time zone [zoneId], at the zone's city: the time of
     * the next Auto theme switch. Polar day or night: the next local midnight (check again then).
     * Zones without coordinates: the next 07:00 or 19:00 local time.
     */
    fun nextTransitionMs(zoneId: String, nowMs: Long): Long =
        nextTransitionMs(TimeZone.getTimeZone(zoneId), ZoneCoordinates.forZone(zoneId), nowMs)

    fun nextTransitionMs(zone: TimeZone, coords: ZoneCoordinates.LatLon?, nowMs: Long): Long {
        var next = Long.MAX_VALUE
        fun offer(ms: Long) {
            if (ms > nowMs && ms < next) next = ms
        }
        // Local dates today, tomorrow and the day after
        for (dayOffset in 0..2) {
            val date = Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_MONTH, dayOffset)
            }
            val year = date.get(Calendar.YEAR)
            val month = date.get(Calendar.MONTH) + 1
            val day = date.get(Calendar.DAY_OF_MONTH)
            if (coords == null) {
                offer(localTimeMs(zone, year, month, day, FALLBACK_DAY_START_HOUR))
                offer(localTimeMs(zone, year, month, day, FALLBACK_DAY_END_HOUR))
                continue
            }
            when (val result = compute(year, month, day, coords.latitude, coords.longitude)) {
                is Result.Times -> {
                    offer(result.sunriseMs)
                    offer(result.sunsetMs)
                }
                // Midnight at the start of the next local date
                else -> offer(localTimeMs(zone, year, month, day, 24))
            }
        }
        return if (next == Long.MAX_VALUE) nowMs + 86_400_000L else next
    }

    private fun localTimeMs(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
            // Wall-clock hour (also on a summer time change day); 24 = the next midnight
            if (hour >= 24) add(Calendar.DAY_OF_MONTH, 1) else set(Calendar.HOUR_OF_DAY, hour)
        }.timeInMillis

    // Minutes after 0h UTC of the date; refined once with the sun's position at the first estimate
    private fun eventMinutes(jd0: Double, latitude: Double, longitude: Double, rising: Boolean): Double {
        var minutes = solarNoonMinutes(jd0, longitude)
        repeat(2) {
            val jd = jd0 + minutes / 1440.0
            val cosHa = cosHourAngle(jd, latitude).coerceIn(-1.0, 1.0)
            val haDeg = Math.toDegrees(Math.acos(cosHa))
            val noon = 720.0 - 4.0 * longitude - equationOfTimeMinutes(jd)
            minutes = if (rising) noon - 4.0 * haDeg else noon + 4.0 * haDeg
        }
        return minutes
    }

    private fun solarNoonMinutes(jd0: Double, longitude: Double): Double {
        // Estimate at mean solar noon, then correct with the equation of time there
        val first = 720.0 - 4.0 * longitude
        return 720.0 - 4.0 * longitude - equationOfTimeMinutes(jd0 + first / 1440.0)
    }

    private fun cosHourAngle(jd: Double, latitude: Double): Double {
        val lat = Math.toRadians(latitude)
        val decl = Math.toRadians(declinationDeg(jd))
        return Math.cos(Math.toRadians(ZENITH_DEG)) / (Math.cos(lat) * Math.cos(decl)) -
            Math.tan(lat) * Math.tan(decl)
    }

    private fun centuries(jd: Double): Double = (jd - 2451545.0) / 36525.0

    private fun meanLongitudeDeg(t: Double): Double {
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0
        return if (l0 < 0) l0 + 360.0 else l0
    }

    private fun meanAnomalyDeg(t: Double): Double = 357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun eccentricity(t: Double): Double = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun obliquityDeg(t: Double): Double {
        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val mean = 23.0 + (26.0 + seconds / 60.0) / 60.0
        val omega = 125.04 - 1934.136 * t
        return mean + 0.00256 * Math.cos(Math.toRadians(omega))
    }

    private fun declinationDeg(jd: Double): Double {
        val t = centuries(jd)
        val m = Math.toRadians(meanAnomalyDeg(t))
        val center = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            Math.sin(2 * m) * (0.019993 - 0.000101 * t) +
            Math.sin(3 * m) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val apparentLong = meanLongitudeDeg(t) + center - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega))
        val sinDecl = Math.sin(Math.toRadians(obliquityDeg(t))) * Math.sin(Math.toRadians(apparentLong))
        return Math.toDegrees(Math.asin(sinDecl))
    }

    private fun equationOfTimeMinutes(jd: Double): Double {
        val t = centuries(jd)
        val eps = Math.toRadians(obliquityDeg(t))
        val l0 = Math.toRadians(meanLongitudeDeg(t))
        val e = eccentricity(t)
        val m = Math.toRadians(meanAnomalyDeg(t))
        val y = Math.tan(eps / 2.0).let { it * it }
        val eq = y * Math.sin(2 * l0) - 2 * e * Math.sin(m) +
            4 * e * y * Math.sin(m) * Math.cos(2 * l0) -
            0.5 * y * y * Math.sin(4 * l0) - 1.25 * e * e * Math.sin(2 * m)
        return 4.0 * Math.toDegrees(eq)
    }
}
