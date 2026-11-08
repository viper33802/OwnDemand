package dev.owndemand.app.domain

/**
 * App colour scheme. [System] follows the
 * device's dark mode; most TVs have none, so on a TV it means dark.
 */
enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    ;

    /** Dark or not, given the device's own setting and whether this is a TV. */
    fun isDark(systemDark: Boolean, isTv: Boolean): Boolean = when (this) {
        System -> systemDark || isTv
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromName(name: String?): ThemeMode? = entries.firstOrNull { it.name == name }
    }
}
