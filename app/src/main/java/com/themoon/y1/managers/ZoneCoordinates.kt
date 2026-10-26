package com.themoon.y1.managers

/**
 * Latitude and longitude of the city of each time zone in the Time Zone setting
 * (SettingsController.TIME_ZONE_IDS), for the sunrise and sunset of the Auto theme.
 * Other zones return null and use the fallback day (see [SunTimes.isDaytime]).
 */
object ZoneCoordinates {

    class LatLon(val latitude: Double, val longitude: Double)

    private val COORDINATES: Map<String, LatLon> = mapOf(
        "Pacific/Honolulu" to LatLon(21.31, -157.86),
        "America/Anchorage" to LatLon(61.22, -149.90),
        "America/Los_Angeles" to LatLon(34.05, -118.24),
        "America/Denver" to LatLon(39.74, -104.99),
        "America/Chicago" to LatLon(41.88, -87.63),
        "America/Mexico_City" to LatLon(19.43, -99.13),
        "America/New_York" to LatLon(40.71, -74.01),
        "America/Bogota" to LatLon(4.71, -74.07),
        "America/Halifax" to LatLon(44.65, -63.57),
        "America/St_Johns" to LatLon(47.56, -52.71),
        "America/Sao_Paulo" to LatLon(-23.55, -46.63),
        "America/Argentina/Buenos_Aires" to LatLon(-34.60, -58.38),
        "Atlantic/South_Georgia" to LatLon(-54.28, -36.51),
        "Atlantic/Azores" to LatLon(37.74, -25.67),
        "GMT" to LatLon(51.48, 0.00), // Greenwich
        "Europe/London" to LatLon(51.51, -0.13),
        "Europe/Lisbon" to LatLon(38.72, -9.14),
        "Europe/Paris" to LatLon(48.86, 2.35),
        "Europe/Berlin" to LatLon(52.52, 13.40),
        "Europe/Madrid" to LatLon(40.42, -3.70),
        "Europe/Rome" to LatLon(41.90, 12.50),
        "Europe/Warsaw" to LatLon(52.23, 21.01),
        "Africa/Lagos" to LatLon(6.52, 3.38),
        "Europe/Athens" to LatLon(37.98, 23.73),
        "Europe/Helsinki" to LatLon(60.17, 24.94),
        "Africa/Cairo" to LatLon(30.04, 31.24),
        "Africa/Johannesburg" to LatLon(-26.20, 28.05),
        "Europe/Moscow" to LatLon(55.76, 37.62),
        "Europe/Istanbul" to LatLon(41.01, 28.98),
        "Asia/Riyadh" to LatLon(24.71, 46.68),
        "Asia/Tehran" to LatLon(35.69, 51.39),
        "Asia/Dubai" to LatLon(25.20, 55.27),
        "Asia/Karachi" to LatLon(24.86, 67.01),
        "Asia/Kolkata" to LatLon(22.57, 88.36),
        "Asia/Kathmandu" to LatLon(27.72, 85.32),
        "Asia/Dhaka" to LatLon(23.81, 90.41),
        "Asia/Bangkok" to LatLon(13.76, 100.50),
        "Asia/Jakarta" to LatLon(-6.21, 106.85),
        "Asia/Shanghai" to LatLon(31.23, 121.47),
        "Asia/Hong_Kong" to LatLon(22.32, 114.17),
        "Asia/Singapore" to LatLon(1.35, 103.82),
        "Australia/Perth" to LatLon(-31.95, 115.86),
        "Asia/Tokyo" to LatLon(35.68, 139.69),
        "Asia/Seoul" to LatLon(37.57, 126.98),
        "Australia/Adelaide" to LatLon(-34.93, 138.60),
        "Australia/Brisbane" to LatLon(-27.47, 153.03),
        "Australia/Sydney" to LatLon(-33.87, 151.21),
        "Pacific/Auckland" to LatLon(-36.85, 174.76)
    )

    fun forZone(zoneId: String): LatLon? = COORDINATES[zoneId]

    val zoneIds: Set<String> get() = COORDINATES.keys
}
