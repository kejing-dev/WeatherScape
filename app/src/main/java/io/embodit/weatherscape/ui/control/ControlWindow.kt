package io.embodit.weatherscape.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.weatherscape.BuildConfig
import io.embodit.weatherscape.R
import io.embodit.weatherscape.domain.City
import io.embodit.weatherscape.domain.Language
import io.embodit.weatherscape.domain.Phase
import io.embodit.weatherscape.domain.SearchStatus
import io.embodit.weatherscape.domain.Settings
import io.embodit.weatherscape.domain.Sky
import io.embodit.weatherscape.domain.Source
import io.embodit.weatherscape.domain.StripMath
import io.embodit.weatherscape.domain.TempUnit
import io.embodit.weatherscape.domain.Texts
import io.embodit.weatherscape.domain.Units
import io.embodit.weatherscape.domain.WState
import io.embodit.weatherscape.platform.Graph
import io.embodit.weatherscape.platform.TAG
import io.embodit.weatherscape.ui.Page
import io.embodit.weatherscape.ui.Ui
import io.embodit.weatherscape.ui.theme.WeatherColors
import kotlin.system.exitProcess

/**
 * The control window (Planar 760 x 660 dp beside the diorama, v0.1.2: was 560 x 620): all 2D UI, three pages switched by the native SpatialUI
 * TabBar (D8): 天气 / 城市 / 设置. No page scrolls (D44); each page spreads its rows between the window padding
 * (SpaceBetween), so the blank outside the content is only the padding (M6). Every count of clickable things per page is
 * <= 7 including the three tabs (B31 / design controlCount).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val zh = graph.zh(settings)
    val unit = graph.unit(settings)
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page == Page.CITIES) graph.flow.refreshCityTemps()
        if (Ui.page != Page.CITIES) graph.flow.cancelReplace()
    }
    LaunchedEffect(st.phase) { if (st.phase == Phase.WELCOME) Ui.page = Page.WEATHER }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.WEATHER -> if (st.phase == Phase.WELCOME) HowToPage(graph) else WeatherPage(st, settings, zh, unit, graph)
            Page.CITIES -> CitiesPage(st, zh, unit, graph)
            Page.SETTINGS -> SettingsPage(settings, zh, unit, graph)
        }
    }
    // The native TabBar is its own system window: labels resolved here, in the app-language context (ui_gates tabbar-lang).
    val tabs = listOf(
        Triple(Page.WEATHER, R.drawable.ic_tab_weather, stringResource(R.string.tab_weather)),
        Triple(Page.CITIES, R.drawable.ic_tab_cities, stringResource(R.string.tab_cities)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

// ------------------------------------------------------------------ Weather (S00 / S02 / S03 / S04 / S08)

/**
 * v0.1.2 (gate B C1-C3 + user 11-13 "panel 要大一点，时刻分到的太细不太好点，时刻应该显示在那个 UI 可以点的区域的横轴"):
 * row 1 = read-only "city · time" + [静音] (always shown, D15); row 4 = 8 tappable 6-hour blocks with the clock ticks on
 * their bottom edge; row 5 = [◀ 1 小时] [回到现在] [1 小时 ▶] (press-and-hold repeats, M18).
 */
@Composable
private fun WeatherPage(st: WState, settings: Settings, zh: Boolean, unit: TempUnit, graph: Graph) {
    val flow = graph.flow
    val loading = st.phase == Phase.LOADING
    val s = st.slice
    val h = st.shown
    val x = s.at(h)
    PageColumn {
        // row 1: 📍 city · time (read only, C3) + mute (right, always shown)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_tab_cities), contentDescription = null, modifier = Modifier.size(26.dp), tint = WeatherColors.InkSecondary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val cityName = st.city?.name(zh) ?: stringResource(R.string.sample_city)
                Txt("$cityName · ${Texts.time(s, h, zh)}", "weather/place", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = WeatherColors.Ink)
                val note = if (st.offline && s.source == Source.CACHE) stringResource(R.string.offline_line, s.ageHours.coerceAtLeast(1)) else Texts.sourceNote(s, zh)
                if (note != null) Txt(note, "weather/source", style = PicoTheme.typography.bodySmall, color = WeatherColors.InkSecondary, maxLines = 2)
            }
            Spacer(Modifier.width(16.dp))
            ToggleButton(stringResource(if (settings.muted) R.string.btn_unmute else R.string.btn_mute), "weather/mute", on = settings.muted,
                icon = if (settings.muted) R.drawable.ic_sound else R.drawable.ic_mute,
                onClick = { graph.settings.update { it.toggleMute() } }, modifier = Modifier.width(168.dp).height(64.dp))
        }
        if (loading) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.loading), "weather/loading", style = PicoTheme.typography.titleMedium)
            }
        } else {
            // row 2: big temperature + sky / feels / high-low
            Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Units.tempText(x.temp, unit), style = PicoTheme.typography.displayLarge.copy(fontSize = 72.sp, fontWeight = FontWeight.Light), color = WeatherColors.Ink,
                    maxLines = 1, onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW weather/temp") })
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt(Texts.sky(x, zh), "weather/sky", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = WeatherColors.Ink)
                    Txt(Texts.feelsLine(x, zh, unit), "weather/feels", style = PicoTheme.typography.bodyLarge, color = WeatherColors.InkSecondary)
                    Txt(Texts.hiLoLine(s, h, zh, unit), "weather/hilo", style = PicoTheme.typography.bodyLarge, color = WeatherColors.InkSecondary)
                }
            }
            // row 3: the advice sentence (or the no-city hint)
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.CenterStart) {
                val line = if (st.noCity && st.city == null) stringResource(R.string.no_city_hint) else Texts.advice(s, h, zh, unit).second
                Txt(line, "weather/advice", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = WeatherColors.Ink, maxLines = 2)
            }
            // row 4: 8 blocks x 6 h (tap = the ring turns to the block's middle hour); the chart is display only
            Strip(st, zh, Modifier.fillMaxWidth().height(STRIP_H)) { b ->
                val t = StripMath.target(b, s.endHour)
                Log.i(TAG, "STRIP block $b -> $t")
                flow.gotoHour(t)
            }
        }
        // row 5: [◀ 1 小时] [回到现在] [1 小时 ▶], 16 dp apart (B31), 72 dp tall (C2)
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val atNow = h == 0 && (st.phase == Phase.NOW || st.phase == Phase.LOADING || st.phase == Phase.WELCOME)
            val target = if (st.phase == Phase.SCRUB) flow.stepTarget() else h
            StepButton(-1, enabled = !loading && target > 0, graph, Modifier.weight(1f).height(72.dp))
            SecondaryButton(stringResource(if (atNow) R.string.btn_now else R.string.btn_back_now), "weather/back", onClick = { flow.backToNow() }, enabled = !atNow && !loading,
                icon = R.drawable.ic_back_now, highlight = !atNow && !loading, modifier = Modifier.weight(1.3f).height(72.dp))
            StepButton(1, enabled = !loading && target < s.endHour, graph, Modifier.weight(1f).height(72.dp))
        }
    }
}

private val STRIP_H = 156.dp

/** ◀ / ▶ 1 hour: a native Button whose press starts the hold (4 h/s after 0.45 s) and whose release stops it (M18). */
@Composable
private fun StepButton(d: Int, enabled: Boolean, graph: Graph, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val why = if (d < 0) "window ◀" else "window ▶"
    LaunchedEffect(src) {
        src.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> graph.flow.holdStart(d, why)
                is PressInteraction.Release, is PressInteraction.Cancel -> graph.flow.holdEnd(why)
            }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) graph.flow.holdEnd("$why greyed") }
    val fg = PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = { graph.flow.holdTap(d, why) }, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 144.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = fg),
        interactionSource = src,
    ) {
        val label = stringResource(R.string.step_hour)
        if (d < 0) Icon(painter = painterResource(R.drawable.ic_chev_left), contentDescription = stringResource(R.string.step_back_desc), modifier = Modifier.size(30.dp), tint = fg)
        // design-style: inherited-content-color Button
        // K5 (QA #3): the icon carries the whole name ("往后 1 小时"); the visible word stays out of the a11y name
        Txt(label, if (d < 0) "weather/step-back" else "weather/step-fwd", style = PicoTheme.typography.titleMedium, color = fg, modifier = Modifier.clearAndSetSemantics {})
        if (d > 0) Icon(painter = painterResource(R.drawable.ic_chev_right), contentDescription = stringResource(R.string.step_fwd_desc), modifier = Modifier.size(30.dp), tint = fg)
    }
}

/** Axis hour word: zh "6 点", en "6 AM" / "Noon" / "6 PM". */
private fun hourWord(hod: Int, zh: Boolean, zhFmt: String): String =
    if (zh) zhFmt.format(hod) else when (hod) { 12 -> "Noon"; 0 -> "12 AM"; else -> if (hod < 12) "$hod AM" else "${hod - 12} PM" }

/**
 * 48 h strip (design row 4, v0.1.2): 8 tappable blocks of 6 h (C1, >= 64 x 64 dp); temperature line + rain bars on top
 * (display only), the clock ticks 0 / 6 / 12 / 18 and the day names on the strip's own bottom axis (the tappable area),
 * the cursor = the ring. Blocks past the end are greyed and do nothing (M18).
 */
@Composable
private fun Strip(st: WState, zh: Boolean, modifier: Modifier, onBlock: (Int) -> Unit) {
    val s = st.slice
    val n = s.hours.size.coerceAtMost(48)
    val end = s.endHour
    val names = listOf(stringResource(R.string.strip_today), stringResource(R.string.strip_tomorrow), stringResource(R.string.strip_next))
    val zhFmt = stringResource(R.string.axis_hour)
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val dayPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val zhFmt2 = zhFmt
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(WeatherColors.CardFace)) {
    // the 8 blocks are real clickable nodes (system pointer, accessibility, pico-cli interact): tap = the block's middle hour
    Row(Modifier.fillMaxSize()) {
        for (b in 0 until StripMath.BLOCKS) {
            val on = StripMath.enabled(b, end)
            val t = StripMath.target(b, end)
            val label = if (on) "${stringResource(R.string.strip_block)} ${Texts.time(s, t, zh)}" else stringResource(R.string.strip_block_off)
            Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label }
                .clickable(enabled = on) { Log.i(TAG, "STRIP tap block $b"); onBlock(b) })
        }
    }
    Canvas(
        Modifier.fillMaxSize(),
    ) {
        val w = size.width; val hgt = size.height
        val bw = w / StripMath.BLOCKS
        val cw = w / 48f
        val axisH = 46.dp.toPx()
        val top = 10.dp.toPx(); val axisY = hgt - axisH; val mid = axisY * 0.50f
        // blocks: the current one is lit, the ones past the end greyed
        val cur = StripMath.blockOf(st.shown)
        for (b in 0 until StripMath.BLOCKS) {
            val x0 = b * bw
            when {
                !StripMath.enabled(b, end) -> drawRect(Color(0x33000000), Offset(x0, 0f), Size(bw, hgt))
                b == cur -> drawRoundRect(WeatherColors.PrimaryContainer, Offset(x0 + 2.dp.toPx(), 2.dp.toPx()), Size(bw - 4.dp.toPx(), hgt - 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
            }
            if (b > 0) drawLine(WeatherColors.Grid, Offset(x0, 0f), Offset(x0, hgt), strokeWidth = 2.dp.toPx())
        }
        // rain bars
        for (i in 0 until n) {
            val p = s.hours[i].popEff / 100f
            if (p <= 0f) continue
            val bh = (axisY - mid - 4.dp.toPx()) * p
            drawRect(WeatherColors.RainBar, Offset(i * cw + cw * 0.18f, axisY - bh), Size(cw * 0.64f, bh))
        }
        // temperature line
        val tMin = s.hours.take(n).minOf { it.temp }; val tMax = s.hours.take(n).maxOf { it.temp }
        val span = (tMax - tMin).coerceAtLeast(1f)
        val path = Path()
        for (i in 0 until n) {
            val y = mid - 6.dp.toPx() - (s.hours[i].temp - tMin) / span * (mid - top - 6.dp.toPx())
            val xx = i * cw + cw / 2
            if (i == 0) path.moveTo(xx, y) else path.lineTo(xx, y)
        }
        drawPath(path, WeatherColors.TempLine, style = Stroke(width = 3.dp.toPx()))
        // the axis on the strip's bottom edge: base line, ticks every hour (long at 0 / 6 / 12 / 18), day lines, labels
        drawLine(WeatherColors.Grid, Offset(0f, axisY), Offset(w, axisY), strokeWidth = 1.5.dp.toPx())
        for (i in 0 until n) {
            val hod = s.hours[i].hod
            val xx = i * cw + cw / 2
            val long = hod % 6 == 0
            drawLine(if (i <= end) WeatherColors.InkSecondary else WeatherColors.Grid, Offset(xx, axisY), Offset(xx, axisY + if (long) 9.dp.toPx() else 4.dp.toPx()), strokeWidth = if (long) 2.dp.toPx() else 1.dp.toPx())
            if (hod == 0 && i > 0) drawLine(WeatherColors.Ink, Offset(i * cw, top), Offset(i * cw, hgt - 4.dp.toPx()), strokeWidth = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        labelPaint.textSize = 14.sp.toPx(); labelPaint.color = WeatherColors.InkSecondary.toArgb()
        dayPaint.textSize = 15.sp.toPx(); dayPaint.color = WeatherColors.Ink.toArgb()
        val ticks = StripMath.ticks(s.hours.map { it.hod }, s.hours.map { it.dayIndex }, end, { d -> names[d.coerceIn(0, 2)] }, { hod -> hourWord(hod, zh, zhFmt) })
        val placed = StripMath.place(ticks, w, 6.dp.toPx(), { h -> h * cw + cw / 2 }, { t -> (if (t.day) dayPaint else labelPaint).measureText(t.text) }, { h -> if (h == 0) 4.dp.toPx() else h * cw + 4.dp.toPx() })
        val baseY = hgt - 12.dp.toPx()
        for ((t, left) in placed) drawContext.canvas.nativeCanvas.drawText(t.text, left, baseY, if (t.day) dayPaint else labelPaint)
        if (BuildConfig.DEBUG) Log.d(TAG, "STRIP axis ${placed.joinToString { it.first.text }}")
        // cursor = the ring's hour
        val cx = st.shown * cw + cw / 2
        drawLine(WeatherColors.Ink, Offset(cx, 4.dp.toPx()), Offset(cx, axisY), strokeWidth = 3.dp.toPx())
        drawCircle(WeatherColors.Ink, 5.dp.toPx(), Offset(cx, 6.dp.toPx()))
    }
    }
}

// ------------------------------------------------------------------ How it works (S01)

@Composable
private fun HowToPage(graph: Graph) {
    PageColumn {
        Txt(stringResource(R.string.howto_title), "howto/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        HowToFigure(Modifier.fillMaxWidth().height(136.dp))
        // C4 (v0.1.2): tapping a block / ◀ ▶ is the main way, the 3D ◀ ▶ and turning the ring come after (D53, Shared Space kept)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.howto_1, R.string.howto_strip, R.string.howto_2, R.string.howto_3).forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(WeatherColors.Primary), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", "howto/n", style = PicoTheme.typography.labelLarge, color = WeatherColors.OnPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Txt(stringResource(id), "howto/line", style = PicoTheme.typography.bodyLarge, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
        PrimaryButton(stringResource(R.string.btn_got_it), "howto/ok", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.fillMaxWidth().height(64.dp))
    }
}

/** The how-to picture: the plinth, the brass ring with an arrow to the right, a small house under a rain cloud. */
@Composable
private fun HowToFigure(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(20.dp)).background(WeatherColors.CardFace)) {
        val w = size.width; val h = size.height
        val cx = w / 2; val cy = h * 0.72f
        // plinth + ring (ellipses seen from the front, above)
        drawOval(Color(0xFF5A3B26), Offset(cx - w * 0.22f, cy - h * 0.06f), Size(w * 0.44f, h * 0.2f))
        drawOval(WeatherColors.Brass, Offset(cx - w * 0.30f, cy - h * 0.10f), Size(w * 0.60f, h * 0.28f), style = Stroke(width = h * 0.06f))
        drawOval(Color(0xFF7FA35A), Offset(cx - w * 0.19f, cy - h * 0.10f), Size(w * 0.38f, h * 0.15f))
        // house
        drawRect(Color(0xFF9A958C), Offset(cx - w * 0.07f, cy - h * 0.26f), Size(w * 0.10f, h * 0.16f))
        val roof = Path().apply { moveTo(cx - w * 0.085f, cy - h * 0.26f); lineTo(cx - w * 0.02f, cy - h * 0.38f); lineTo(cx + w * 0.045f, cy - h * 0.26f); close() }
        drawPath(roof, Color(0xFF4A4C50))
        // rain cloud + rain
        drawCircle(Color(0xFF8C9199), h * 0.09f, Offset(cx + w * 0.02f, h * 0.18f))
        drawCircle(Color(0xFF8C9199), h * 0.11f, Offset(cx + w * 0.09f, h * 0.15f))
        drawCircle(Color(0xFF8C9199), h * 0.08f, Offset(cx + w * 0.16f, h * 0.19f))
        for (k in 0 until 5) {
            val x0 = cx + w * (0.0f + k * 0.04f)
            drawLine(WeatherColors.RainBar, Offset(x0, h * 0.30f), Offset(x0 - w * 0.01f, h * 0.40f), strokeWidth = 3.dp.toPx())
        }
        // the 48 h strip on the right with a tapped hour (the no-drag way, D53)
        val sx = w * 0.82f; val sw = w * 0.15f; val sy = h * 0.40f; val sh = h * 0.30f
        drawRoundRect(Color(0xFFFFFFFF), Offset(sx, sy), Size(sw, sh), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        for (k in 0 until 8) {
            val bh = sh * (0.15f + 0.6f * ((k * 37 % 10) / 10f))
            drawRect(WeatherColors.RainBar, Offset(sx + sw * (0.06f + k * 0.11f), sy + sh - 4.dp.toPx() - bh * 0.7f), Size(sw * 0.07f, bh * 0.7f))
        }
        val tx = sx + sw * 0.6f
        drawLine(WeatherColors.Ink, Offset(tx, sy + 3.dp.toPx()), Offset(tx, sy + sh - 3.dp.toPx()), strokeWidth = 3.dp.toPx())
        drawCircle(WeatherColors.Primary, h * 0.07f, Offset(tx + w * 0.012f, sy + sh + h * 0.10f))
        drawCircle(WeatherColors.Primary, h * 0.11f, Offset(tx + w * 0.012f, sy + sh + h * 0.10f), style = Stroke(width = 2.dp.toPx()))
        // turn-right arrow on the ring's front
        val ay = cy + h * 0.16f
        drawLine(WeatherColors.Primary, Offset(cx - w * 0.14f, ay), Offset(cx + w * 0.14f, ay), strokeWidth = 5.dp.toPx())
        val head = Path().apply { moveTo(cx + w * 0.17f, ay); lineTo(cx + w * 0.13f, ay - h * 0.05f); lineTo(cx + w * 0.13f, ay + h * 0.05f); close() }
        drawPath(head, WeatherColors.Primary)
    }
}

// ------------------------------------------------------------------ Cities (S06 / S07)

@Composable
private fun CitiesPage(st: WState, zh: Boolean, unit: TempUnit, graph: Graph) {
    val flow = graph.flow
    val search = st.search
    PageColumn {
        Txt(stringResource(R.string.cities_title), "cities/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        SearchField(
            value = search.query,
            onValueChange = { flow.searchInput(it) },
            onSearch = { flow.searchInput(it) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
        )
        val replace = search.replace
        // v0.1.1 (QA #1 card blank 22-27 %): one 340 dp area; no state leaves a big empty card. Rows / cards fill it.
        Column(Modifier.fillMaxWidth().height(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                replace != null -> Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(WeatherColors.CardFace).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Txt(stringResource(R.string.replace_ask, replace.second.name(zh), replace.first.name(zh)), "cities/replace", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = WeatherColors.Ink, maxLines = 2)
                    Txt(stringResource(R.string.replace_hint), "cities/replace-hint", style = PicoTheme.typography.bodyMedium, color = WeatherColors.InkSecondary, maxLines = 2)
                    Txt(stringResource(R.string.replace_now, st.cities.joinToString(" · ") { it.name(zh) }), "cities/replace-now", style = PicoTheme.typography.bodyMedium, color = WeatherColors.InkSecondary, maxLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SecondaryButton(stringResource(R.string.btn_cancel), "cities/replace-no", onClick = { flow.cancelReplace() }, modifier = Modifier.weight(1f).height(64.dp))
                        SecondaryButton(stringResource(R.string.btn_replace), "cities/replace-yes", highlight = true, onClick = { flow.confirmReplace(); Ui.page = Page.WEATHER }, modifier = Modifier.weight(1f).height(64.dp))
                    }
                }
                search.query.isNotBlank() && search.status == SearchStatus.RESULTS -> {
                    search.results.forEach { c ->
                        CityRow(c.name(zh), c.place(zh), null, "cities/result", current = false, modifier = Modifier.height(84.dp)) { flow.pickResult(c); if (flow.value.search.replace == null) Ui.page = Page.WEATHER }
                    }
                    // fewer than 3 results: a one-line hint in the rest (no empty hole, D44)
                    if (search.results.size < 3) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.results_more), "cities/results-more", style = PicoTheme.typography.bodyMedium, color = WeatherColors.InkSecondary, maxLines = 2)
                    }
                }
                search.query.isNotBlank() && search.status == SearchStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                search.query.isNotBlank() && search.status != SearchStatus.IDLE -> {
                    // no result / offline: a compact note on top, your cities stay tappable below. Offline never asks for
                    // another spelling (QA #1: the spelling tip showed while offline).
                    val offline = search.status == SearchStatus.ERROR
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(WeatherColors.CardFace).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(36.dp), tint = WeatherColors.InkSecondary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Txt(stringResource(if (offline) R.string.search_offline else R.string.no_result), "cities/msg", style = PicoTheme.typography.titleMedium, color = WeatherColors.Ink, maxLines = 2)
                            Txt(stringResource(if (offline) R.string.search_offline_tip else R.string.search_tip), "cities/tip", style = PicoTheme.typography.bodyMedium, color = WeatherColors.InkSecondary, maxLines = 2)
                        }
                    }
                    SavedRows(st, zh, unit, graph, Modifier.weight(1f))
                }
                search.query.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(stringResource(R.string.search_more), "cities/more", style = PicoTheme.typography.bodyLarge, color = WeatherColors.InkSecondary)
                }
                else -> SavedRows(st, zh, unit, graph, Modifier.weight(1f))
            }
        }
        Txt(stringResource(R.string.attribution), "cities/attr", style = PicoTheme.typography.bodySmall, color = WeatherColors.InkSecondary)
    }
}

/** "Your cities": the saved ones (now temperature + sky), fewer than 3 topped up with built-in cities (tap = add). */
@Composable
private fun SavedRows(st: WState, zh: Boolean, unit: TempUnit, graph: Graph, modifier: Modifier) {
    val flow = graph.flow
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(stringResource(R.string.saved_cities), "cities/saved", style = PicoTheme.typography.labelLarge, color = WeatherColors.InkSecondary)
        st.cities.forEach { c ->
            val now = st.nowTemps[c.id] ?: if (c.id == st.city?.id) st.slice.at(0).let { it.temp to it.code } else null
            val right = now?.let { (t, code) -> "${Units.tempText(t, unit)} ${Texts.skyOfCode(code, zh)}" }
            CityRow(c.name(zh), c.place(zh), right, "cities/saved-row", current = c.id == st.city?.id, modifier = Modifier.weight(1f)) { flow.selectCity(c); Ui.page = Page.WEATHER }
        }
        // fewer than 3 saved: built-in cities fill the empty slots (tap = add it), so the page never shows a hole (D44 blank)
        val saved = st.cities.map { it.id }.toSet()
        val suggest = io.embodit.weatherscape.domain.DefaultCities.ALL.filter { it.id !in saved }
            .sortedBy { if (it.countryEn == st.city?.countryEn) 0 else 1 }.take((3 - st.cities.size).coerceAtLeast(0))
        suggest.forEach { c -> CityRow("+  " + c.name(zh), c.place(zh), null, "cities/suggest-row", current = false, modifier = Modifier.weight(1f)) { flow.pickResult(c); if (flow.value.search.replace == null) Ui.page = Page.WEATHER } }
    }
}

@Composable
private fun CityRow(name: String, place: String, right: String?, tag: String, current: Boolean, modifier: Modifier = Modifier.height(72.dp), onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (current) WeatherColors.PrimaryContainer else WeatherColors.CardFace)
            .clickable { Log.i(TAG, "CITY row $name"); onClick() }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(name, "$tag/name", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = WeatherColors.Ink)
            if (place.isNotBlank()) Txt(place, "$tag/place", style = PicoTheme.typography.bodySmall, color = WeatherColors.InkSecondary)
        }
        if (right != null) Txt(right, "$tag/now", style = PicoTheme.typography.titleMedium, color = WeatherColors.Ink)
    }
}

// ------------------------------------------------------------------ Settings (S09 / S10)

@Composable
private fun SettingsPage(settings: Settings, zh: Boolean, unit: TempUnit, graph: Graph) {
    val context = LocalContext.current
    val confirm = Ui.exitConfirm
    PageColumn {
        Txt(stringResource(R.string.settings_title), "settings/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        LabeledRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), if (zh) Language.ZH else Language.EN, "settings/lang",
                { l -> if (!confirm) graph.settings.update { it.copy(language = l) } }, Modifier.fillMaxWidth().height(64.dp), enabled = !confirm)
        }
        LabeledRow(stringResource(R.string.settings_unit), "settings/unit") {
            Segments(listOf(TempUnit.C to "°C", TempUnit.F to "°F"), unit, "settings/unit",
                { u -> if (!confirm) graph.settings.update { it.copy(unit = u) } }, Modifier.fillMaxWidth().height(64.dp), enabled = !confirm)
        }
        LabeledRow(stringResource(R.string.settings_sound), "settings/sound") {
            val names = listOf(R.string.sound_off, R.string.sound_low, R.string.sound_mid, R.string.sound_high)
            Segments((0..3).map { it to "${stringResource(names[it])} ${Settings.PERCENT[it]}%" }, settings.sound, "settings/sound",
                { v -> if (!confirm) graph.settings.update { it.withSound(v) } }, Modifier.fillMaxWidth().height(64.dp), small = true, enabled = !confirm)
        }
        if (confirm) {
            // S10: the confirmation replaces the quit row in place; the three rows above are greyed out
            Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(WeatherColors.CardFace).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(stringResource(R.string.exit_ask), "settings/exit-ask", style = PicoTheme.typography.titleMedium, color = WeatherColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-no", onClick = { Ui.exitConfirm = false }, modifier = Modifier.width(120.dp).height(64.dp))
                SecondaryButton(stringResource(R.string.btn_exit_yes), "settings/exit-yes", danger = true, icon = R.drawable.ic_exit, onClick = { exitApp(context, graph) }, modifier = Modifier.width(120.dp).height(64.dp))
            }
        } else {
            SecondaryButton(stringResource(R.string.btn_exit), "settings/exit", danger = true, icon = R.drawable.ic_exit,
                onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT") }, modifier = Modifier.fillMaxWidth().height(64.dp))
        }
        Txt(stringResource(R.string.about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = WeatherColors.InkSecondary, maxLines = 2)
    }
}

@Composable
private fun LabeledRow(label: String, tag: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = WeatherColors.InkSecondary)
        content()
    }
}

/** D26 / D44 exit: release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                // the capsule only draws mainContent (supportContent never shows, pico-cli-feedback): the word under the icon
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        // the word follows the item's content colour like the icon does (white on the selected capsule)
                        val fg = LocalContentColor.current
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = fg, maxLines = 1)
                    }
                },
            )
        }
    }
}

@Suppress("unused")
private val unusedSky = Sky.CLEAR
