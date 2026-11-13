package io.embodit.weatherscape.domain

enum class Language { SYSTEM, ZH, EN }

/**
 * App settings (design dataModel.settings). [sound] 0 = off, 1 low, 2 mid, 3 high (0 / 35 / 65 / 100 %, default mid,
 * D15); the weather page's [静音] and the settings row are the same value; [lastSound] is what unmute goes back to.
 */
data class Settings(
    val language: Language = Language.SYSTEM,
    val unit: TempUnit? = null,
    val sound: Int = 2,
    val lastSound: Int = 2,
    val seenHowTo: Boolean = false,
) {
    val muted: Boolean get() = sound == 0

    fun toggleMute(): Settings = if (muted) copy(sound = lastSound.coerceIn(1, 3)) else copy(sound = 0, lastSound = sound)

    fun withSound(level: Int): Settings = if (level == 0) copy(sound = 0, lastSound = if (sound > 0) sound else lastSound) else copy(sound = level.coerceIn(1, 3), lastSound = level.coerceIn(1, 3))

    /** Effective language: SYSTEM follows the system locale. */
    fun zh(systemZh: Boolean): Boolean = when (language) { Language.ZH -> true; Language.EN -> false; Language.SYSTEM -> systemZh }

    /** °C in Chinese; English follows the system region (US -> °F) unless the user picked one. */
    fun unit(systemZh: Boolean, systemRegionF: Boolean): TempUnit = unit ?: if (zh(systemZh)) TempUnit.C else if (systemRegionF) TempUnit.F else TempUnit.C

    companion object {
        val PERCENT = intArrayOf(0, 35, 65, 100)
    }
}

/** Sound events (design sound.list). */
enum class Sound { TICK, RAIN, THUNDER, WIND, PAPER, CLACK }
