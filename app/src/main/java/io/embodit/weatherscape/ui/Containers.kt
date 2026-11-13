package io.embodit.weatherscape.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The diorama volume is the default container (manifest). */
object Containers {
    const val VOLUME = "WeatherScapeVolume"
    const val CONTROL = "WeatherScapeControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the control window (design panels.control: 天气 / 城市 / 设置), switched by the native TabBar. */
enum class Page(val stateId: String) { WEATHER("S02"), CITIES("S06"), SETTINGS("S09") }

/** Window-page state shared process-wide so debug commands and the volume can switch pages (S11 reopens on the same page). */
object Ui {
    var page by mutableStateOf(Page.WEATHER)
    var exitConfirm by mutableStateOf(false)
}
