package io.embodit.weatherscape.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Text
import io.embodit.weatherscape.platform.TAG
import io.embodit.weatherscape.ui.theme.WeatherColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null, highlight: Boolean = false) {
    val strong = danger || highlight
    val fg = if (strong) WeatherColors.OnError else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when { danger -> WeatherColors.Error; highlight -> WeatherColors.Primary; else -> PicoTheme.colorScheme.fillSecondary },
            contentColor = fg,
        ),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
        else Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) WeatherColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(6.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    // B19 / B31: the control itself is 64 dp tall (the default track is ~37 dp); greyed while S10 asks.
    SegmentControl(modifier = modifier.height(64.dp).alpha(if (enabled) 1f else 0.4f)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = if (small) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/**
 * v0.1.6 (QA #6 q6-2, B20): the sound row as a 4-stop slider (关 / 低 / 中 / 高): taps and drags both change the stop.
 * Bound to a free Float through [VolumeDrag] (a tap or a short drag is never swallowed); the SpatialUI Slider keeps the
 * first onValueChange it sees, so the callbacks read [onChange] through rememberUpdatedState (gate slider-stale).
 * B19: the track is >= 100 dp wide and 48 dp tall; the stop name + percent sits on the right.
 */
@Composable
internal fun VolumeSlider(level: Int, label: String, name: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val drag = androidx.compose.runtime.remember { VolumeDrag(level, stops = 4) }
    var pos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(level.toFloat()) }
    val cb = androidx.compose.runtime.rememberUpdatedState(onChange)
    val on = androidx.compose.runtime.rememberUpdatedState(enabled)
    androidx.compose.runtime.LaunchedEffect(level) { drag.sync(level); pos = drag.pos }
    Row(modifier.alpha(if (enabled) 1f else 0.4f).clearAndSetSemantics {
        contentDescription = name
        stateDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), 0f..3f, steps = 2)
        setProgress { v ->
            if (!on.value) return@setProgress false
            val n = kotlin.math.round(v).toInt().coerceIn(0, 3)
            Log.i(TAG, "VOLUME a11y $n")
            drag.sync(n); pos = n.toFloat()
            if (n != level) cb.value(n)
            true
        }
    }, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = pos,
            onValueChange = { v -> if (on.value) { val changed = drag.move(v); pos = drag.pos; if (changed != null) { Log.i(TAG, "VOLUME live $changed"); cb.value(changed) } } },
            onValueChangeFinished = { if (on.value) { val changed = drag.finish(); pos = drag.pos; Log.i(TAG, "VOLUME snap ${drag.level}"); if (changed != null) cb.value(changed) } },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..3f,
        )
        Box(Modifier.width(16.dp))
        Txt(label, "settings/sound-level", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(112.dp), align = TextAlign.End)
    }
}
