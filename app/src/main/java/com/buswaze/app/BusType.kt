package com.buswaze.app

/**
 * Bus profiles with real-world dimensions.
 * height/width/length in meters, weight in tons.
 * These are sent to the routing engine so routes avoid
 * roads the selected bus physically cannot drive.
 */
enum class BusType(
    val labelRes: Int,
    val emoji: String,
    val height: Double,
    val width: Double,
    val length: Double,
    val weightTons: Double
) {
    MINI(R.string.bus_mini, "🚐", 2.9, 2.1, 7.0, 5.5),
    SMALL(R.string.bus_small, "🚌", 3.0, 2.4, 9.5, 12.0),
    NORMAL(R.string.bus_normal, "🚍", 3.3, 2.55, 12.0, 19.0),
    ARMORED(R.string.bus_armored, "🛡️", 3.4, 2.55, 12.5, 23.0);

    companion object {
        fun fromName(name: String?): BusType =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
