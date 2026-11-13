package io.embodit.weatherscape

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.weatherscape.ui.Containers
import io.embodit.weatherscape.ui.control.ControlWindow
import io.embodit.weatherscape.ui.scene.VolumeScreen
import io.embodit.weatherscape.ui.theme.WeatherTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the diorama volume (default container,
 * 0.66 x 0.54 x 0.68 m from the manifest) and the control window (Planar 560 x 620 dp, fixed) to its right. Every open
 * (first open, the knob in S11, the app icon again) uses Placement.right(volume) (profile D44: Shared Space never
 * promises "in front of you"; the user moves the window with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            WeatherTheme { VolumeScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 620.dp),
            placement = { containers.firstOrNull { it.id == Containers.VOLUME }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            WeatherTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 620.dp, maxWidth = 560.dp, maxHeight = 620.dp)) { ControlWindow() }
            }
        }
    }
